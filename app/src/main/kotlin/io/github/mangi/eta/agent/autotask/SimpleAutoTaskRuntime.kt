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
        // runtimeConfigFor 是 suspend；这里已在后台线程（广播回调 / IO），
        // 用 runBlocking 等待一次数据库读取，不会阻塞主线程。
        val config = kotlinx.coroutines.runBlocking { RuntimeConfigRepository.runtimeConfigFor(null) }
            ?: return "未配置可用的模型"
        val provider: AgentProviderClient = ProviderClientFactory.getClient(config)

        val reply = StringBuilder()
        AgentModelClient.complete(
            config = config,
            prompt = buildPrompt(task),
            toolExecutor = io.github.mangi.eta.agent.model.AgentModelClient.ToolExecutor { call ->
                io.github.mangi.eta.agent.model.AgentModelClient.ToolResult(
                    "失败：自动任务第一版未接入工具执行链（${call.name}）",
                )
            },
            provider = provider,
            runController = AgentRunController(),
            // 关键：把轮数上限传下去，避免无人看管下无限循环
            history = emptyList(),
        )
        return reply.toString().ifBlank { "（无输出）" }
    }

    private fun buildPrompt(task: AgentTask): String = buildString {
        appendLine("[自动任务] ${task.title}")
        appendLine("这是无人值守期间自动触发的任务，最多执行 ${task.maxRounds} 轮。")
        appendLine("要求：只做只读操作；不要询问用户；完成后用一段话总结结果。")
        appendLine()
        append(task.prompt)
    }
}
