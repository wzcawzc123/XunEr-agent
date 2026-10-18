package io.github.mangi.eta.agent.autotask

import android.content.Context
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.agent.model.AgentProviderClient
import io.github.mangi.eta.agent.model.ProviderClientFactory
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.data.db.EtaDatabase

/**
 * [AutoTaskRuntime] 的实现：读任务 → 读状态 → 决策 → 执行一条。
 *
 * 执行方式刻意选了 [AgentModelClient.complete] 而不是 `AgentRuntimeService`：
 * 后者是**绑定式服务**（`ACTION_BIND`），需要一个存活的 binder 客户端，
 * 而自动任务运行在广播回调里、没有 UI 连接 —— 硬接反而要造一套假的绑定生命周期。
 * `complete()` 是同一套 Agent 循环的纯函数入口，`AgentMemoryStore` 已有同样用法
 * （见其中 `rollupMemory()`）。
 *
 * 并发安全：广播可能连发（充电时可能同时收到 POWER_CONNECTED 与网络可用），
 * 所以用一把锁把「决策 + 执行」串行化 —— 否则两条任务会被同时启动。
 */
internal class SimpleAutoTaskRuntime(
    private val database: EtaDatabase,
) : AutoTaskRuntime {

    private val lock = Any()

    override fun tryRunOnce(context: Context) {
        // 快速判空：没有任务时完全不打锁，避免无谓开销（该回调会被频繁触发）
        val store = AutoTaskStore(database.agentTaskDao())
        val tasks = store.runnableBlocking()
        if (tasks.isEmpty()) return

        synchronized(lock) {
            runCatching { runOnceLocked(context, store, tasks) }
                .onFailure {
                    AndroidAgentLogger.warnThrottled("auto_task_run_failed") {
                        "自动任务执行失败: type=${it.javaClass.simpleName}"
                    }
                }
        }
    }

    private fun runOnceLocked(context: Context, store: AutoTaskStore, tasks: List<AgentTask>) {
        val state = DeviceStateReader.read(context)
        val now = System.currentTimeMillis()
        when (val decision = AutoTaskScheduler.decide(tasks, state, now)) {
            AutoTaskScheduler.Decision.Idle -> return
            is AutoTaskScheduler.Decision.Run -> execute(context, store, decision.task, now)
        }
    }

    private fun execute(context: Context, store: AutoTaskStore, task: AgentTask, now: Long) {
        // 护栏：第一版只允许只读任务
        if (!task.readOnly) {
            store.saveBlocking(task.copy(lastRunAt = now, lastResult = "已拒绝：第一版仅支持只读任务"))
            return
        }

        val result = runCatching { runAgentRound(context, task) }
            .getOrElse { error ->
                "执行失败：${error.message ?: error.javaClass.simpleName}"
            }

        // 关键：结果必须写回会话，否则通知点开是空的。
        // 写两条：一条"自动触发"的 user 消息（让上下文读起来连贯）+ 一条 assistant 回复。
        runCatching { kotlinx.coroutines.runBlocking { persistRound(database.conversationDao(), task, result, now) } }
            .onFailure {
                AndroidAgentLogger.warnThrottled("auto_task_persist_failed") {
                    "自动任务结果写回会话失败: type=${it.javaClass.simpleName}"
                }
            }

        store.saveBlocking(task.copy(lastRunAt = now, lastResult = result))
        AutoTaskNotifier.notifyFinished(context, task, result)
    }

    /**
     * 跑一轮完整的 Agent（含工具），返回结果摘要。
     *
     * 上下文里明确告知这是自动任务：模型需要知道「没人看着」，
     * 才不会再问问题、也不会做需要确认的操作。
     */
    private fun runAgentRound(context: Context, task: AgentTask): String {
        val config = kotlinx.coroutines.runBlocking { RuntimeConfigRepository.runtimeConfigFor(null) }
            ?: return "未配置可用的模型"
        val provider: AgentProviderClient = ProviderClientFactory.getClient(config)

        // 核心修正：**在任务所属会话里追加一轮**，不新建会话。
        // 这样自动轮能继承原会话上下文（知道你们之前聊什么），会话列表也不会被刷屏。
        // 任务没绑会话时（旧数据）退化为临时会话，保证仍能执行。
        val targetConversationId = task.conversationId
            ?: "auto-task-${task.id}-${System.currentTimeMillis()}"
        val dao = database.conversationDao()

        // 读该会话已有历史；旧数据没绑会话时从空历史开始
        val history = if (task.conversationId != null) {
            kotlinx.coroutines.runBlocking { loadHistory(dao, targetConversationId) }
        } else {
            kotlinx.coroutines.runBlocking {
                dao.insertConversationRow(
                    io.github.mangi.eta.data.db.ConversationEntity(
                        id = targetConversationId,
                        title = "[自动] ${task.title}",
                        thinkingEnabled = false,
                        createdAt = System.currentTimeMillis(),
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
            }
            emptyList()
        }

        val tools = io.github.mangi.eta.agent.tool.AgentLocalTools(
            context = context,
            logger = AndroidAgentLogger,
            conversationId = targetConversationId,
        )

        val reply = AgentModelClient.complete(
            config = config,
            prompt = buildPrompt(task),
            toolExecutor = tools,
            provider = provider,
            runController = AgentRunController(),
            history = history,
            sessionId = targetConversationId,
        )

        return reply.content.ifBlank { "（模型未返回内容）" }
    }

    /** 读会话历史（historyJson → ConversationMessage 列表）。 */
    private suspend fun loadHistory(
        dao: io.github.mangi.eta.data.db.ConversationDao,
        conversationId: String,
    ): List<AgentModelClient.ConversationMessage> = runCatching {
        val raw = dao.historyJsonFor(conversationId) ?: return@runCatching emptyList()
        io.github.mangi.eta.agent.model.AgentConversationCodec.decodeTranscript(raw)
    }.getOrElse { emptyList() }

    private fun buildPrompt(task: AgentTask): String = buildString {
        appendLine("[自动任务] ${task.title}")
        appendLine("这是无人值守期间自动触发的任务，最多执行 ${task.maxRounds} 轮。")
        appendLine("要求：只做只读操作；不要询问用户；完成后用一段话总结结果。")
        appendLine()
        append(task.prompt)
    }

    /**
     * 把这一轮追加到会话。
     *
     * 写两条而不是一条：只有 assistant 消息的话，会话历史里会缺"这一轮因何而起"，
     * 用户往上翻看不懂；补一条带 ⚡ 标记的 user 消息，语义完整且一眼可辨是自动轮。
     */
    private suspend fun persistRound(
        dao: io.github.mangi.eta.data.db.ConversationDao,
        task: AgentTask,
        result: String,
        now: Long,
    ) {
        val conversationId = task.conversationId ?: return  // 旧数据没绑会话，不写
        val start = dao.nextSortIndex(conversationId)
        dao.insertMessages(
            listOf(
                io.github.mangi.eta.data.db.ConversationMessageEntity(
                    id = "auto-user-$now",
                    conversationId = conversationId,
                    sortIndex = start,
                    type = "user",
                    content = "⚡ [自动任务] ${task.title}（${task.condition.describe()}）",
                ),
                io.github.mangi.eta.data.db.ConversationMessageEntity(
                    id = "auto-assistant-$now",
                    conversationId = conversationId,
                    sortIndex = start + 1,
                    type = "assistant",
                    content = result,
                ),
            ),
        )
        dao.touchConversation(conversationId, now)
    }
}