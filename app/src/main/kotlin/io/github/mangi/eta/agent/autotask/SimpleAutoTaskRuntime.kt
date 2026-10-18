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

    override fun tryRunOnce(context: Context) {
        // **锁必须是进程级的**：AutoTaskService 每次 onStartCommand 都会 new 一个
        // SimpleAutoTaskRuntime，实例字段锁各管各的，两条 am 同时到达时会并行执行
        // 两条任务（各自真金白银跑一轮 Agent）。审查时发现的。
        //
        // **读取也必须进锁**（M1）：原来「锁外快照 + 锁内执行」在并发唤醒下，
        // 两个唤醒线程可能都读到同一份就绪列表，各自跑一遍同一条任务。
        synchronized(RUN_LOCK) {
            runCatching {
                val store = AutoTaskStore(database.agentTaskDao())
                // 崩溃恢复先于决策：被恢复停用的一次性任务不能又被选中跑一遍。
                // 即使当前没有就绪任务，残留执行中标记也必须清理
                //（否则任务被停用/删除后标记永远挂着，重新启用时会被误判为中断）。
                val markers = AutoTaskExport.inProgressEntries(context)
                if (markers.isNotEmpty()) recoverInterruptedRuns(context, store, markers)
                val tasks = store.runnableBlocking()
                diag(context, "tryRunOnce: runnable=${tasks.size} ids=${tasks.map { it.id }}")
                if (tasks.isEmpty()) return@runCatching
                runOnceLocked(context, store, tasks)
            }.onFailure {
                AndroidAgentLogger.warnThrottled("auto_task_run_failed") {
                    "自动任务执行失败: type=${it.javaClass.simpleName}"
                }
            }
        }
    }

    /**
     * H2 崩溃恢复：离线轮中途被杀（系统回收/进程崩溃）后，执行中标记残留。
     * 下次唤醒先按标记判定上一轮是「真中断」还是「已完成只差清标记」：
     * - 任务 lastRunAt >= 标记开始时间 → 上一轮实际完成（execute 里标记与落库用同一个
     *   now，完成路径二者相等），只清残留标记；
     * - 否则 → 真中断：记录结果并消费掉这次执行（一次性任务停用，杜绝无时间限制
     *   的任务每次唤醒都重跑 → 双烧费；周期任务只推进 lastRunAt，等下一次到点）。
     */
    private fun recoverInterruptedRuns(context: Context, store: AutoTaskStore, markers: Map<String, Long>) {
        val byId = store.allBlocking().associateBy { it.id }
        markers.forEach { (taskId, startedAt) ->
            val task = byId[taskId]
            if (task == null || !task.enabled) {
                // 已删除或已停用：残留标记直接清掉，不动数据
                AutoTaskExport.clearInProgress(context, taskId)
                return@forEach
            }
            if ((task.lastRunAt ?: 0L) >= startedAt) {
                // 上一轮已完成（lastRunAt 已推进，只剩清标记这一步没做）——只清残留标记
                AutoTaskExport.clearInProgress(context, taskId)
                return@forEach
            }
            val repeating = task.schedule?.repeating == true
            store.saveBlocking(
                task.copy(
                    lastRunAt = startedAt,
                    lastResult = if (repeating) {
                        "上次执行被中断（进程被杀），已记录，等下一次到点"
                    } else {
                        "上次执行被中断（进程被杀），已按完成处理，不会重跑"
                    },
                    // 一次性任务：消费掉这次执行（否则每次唤醒都重跑 → 双烧费）。
                    // 周期任务：只推进 lastRunAt，保持 active，下一次到点照常。
                    active = if (repeating) task.active else false,
                ),
            )
            AutoTaskExport.clearInProgress(context, taskId)
            AndroidAgentLogger.warnThrottled("auto_task_interrupted_recovered") {
                "自动任务 $taskId 上次离线执行被中断，已恢复记录（startedAt=$startedAt）"
            }
        }
    }

    private fun runOnceLocked(context: Context, store: AutoTaskStore, tasks: List<AgentTask>) {
        val state = DeviceStateReader.read(context)
        val now = System.currentTimeMillis()
        tasks.forEach { t ->
            diag(
                context,
                "check ${t.id}: enabled=${t.enabled} active=${t.active} finished=${t.isFinished} " +
                    "due=${t.isDue(now)} cond=${t.condition.satisfiedBy(state)} lastRun=${t.lastRunAt}",
            )
        }
        diag(context, "state=$state")
        when (val decision = AutoTaskScheduler.decide(tasks, state, now)) {
            AutoTaskScheduler.Decision.Idle -> diag(context, "decision=Idle")
            is AutoTaskScheduler.Decision.Run -> {
                diag(context, "decision=Run ${decision.task.id}")
                execute(context, store, decision.task, now)
            }
        }
    }

    /** 诊断日志：真机上 logcat 易被冲掉，判定链的每一步落到文件里最可靠。 */
    private fun diag(context: Context, message: String) {
        runCatching {
            val f = java.io.File(java.io.File(context.filesDir, "autotask"), "diag.log")
            f.parentFile?.mkdirs()
            f.appendText("${System.currentTimeMillis()} $message\n")
        }
    }

    private fun execute(context: Context, store: AutoTaskStore, task: AgentTask, now: Long) {
        // 护栏：第一版只允许只读任务
        if (!task.readOnly) {
            store.saveBlocking(task.copy(lastRunAt = now, lastResult = "已拒绝：第一版仅支持只读任务"))
            return
        }

        // ① 优先：投递到会话，走**正常对话流程**（等价用户发消息）。
        //    对齐 dsh 的 `agent.followup(message)`：只有这样才会进 UI 状态、界面实时刷新，
        //    落库与生命周期也由正常流程负责。第一版直接调 complete() 是错的（界面看不到）。
        val prompt = buildPrompt(task)
        if (AutoTaskDispatcher.tryDeliver(task.conversationId, prompt, task.maxRounds)) {
            diag(context, "delivered via session (normal flow) ${task.id}")
            val nextRunCount = task.runCount + 1
            val stillActiveDelivered = task.schedule?.repeating == true && nextRunCount < task.maxRuns
            store.saveBlocking(
                task.copy(
                    lastRunAt = now,
                    lastResult = if (nextRunCount >= task.maxRuns) {
                        "已投递 $nextRunCount 次（达上限，自动停止）"
                    } else {
                        "已投递到会话，结果见当前对话（第 $nextRunCount/${task.maxRuns} 次）"
                    },
                    runCount = nextRunCount,
                    active = stillActiveDelivered,
                ),
            )
            store.exportTo(context)
            return
        }

        // ② App 活着但当前有轮次在跑（或目标不是当前会话）：**排队**，等本轮结束再投递。
        //    这正是用户要的「轮次结束 → 自动发起新轮次」，而不是降级成看不见的离线记录。
        val target = task.conversationId
        if (AutoTaskDispatcher.hasHandler() && !target.isNullOrBlank()) {
            AutoTaskDispatcher.enqueue(task.id, target, prompt, task.maxRounds)
            diag(context, "queued until current run ends ${task.id}")
            val nextRunCountQueued = task.runCount + 1
            store.saveBlocking(
                task.copy(
                    lastRunAt = now,
                    lastResult = "已排队，等当前对话结束后投递（第 $nextRunCountQueued/${task.maxRuns} 次）",
                    runCount = nextRunCountQueued,
                    active = task.schedule?.repeating == true && nextRunCountQueued < task.maxRuns,
                ),
            )
            store.exportTo(context)
            return
        }

        // ③ 降级：App 不在前台（UI 未注册投递器）—— 离线执行并写回历史 + 通知，至少数据不丢。
        diag(context, "fallback to offline run ${task.id}")
        // H2：执行前写「执行中」标记；进程中途被杀时残留，下次唤醒由 recoverInterruptedRuns 判定。
        // 清除放在整个离线分支的最后（覆盖跑轮+落库+导出+通知全窗口），
        // 即使清除这一步没执行，完成路径 lastRunAt 与 startedAt 相等，恢复逻辑也只清标记不误报。
        AutoTaskExport.markInProgress(context, task.id, now)
        val result = runCatching { runAgentRound(context, task) }
            .getOrElse { error ->
                "执行失败：${error.message ?: error.javaClass.simpleName}"
            }

        // 关键：结果必须写回会话，否则通知点开是空的。
        // 写两条：一条"自动触发"的 user 消息（让上下文读起来连贯）+ 一条 assistant 回复。
        runCatching { kotlinx.coroutines.runBlocking { persistRound(database.conversationDao(), task, result, now) } }
            .onFailure { error ->
                // 同时落一份文件：logcat 环形缓冲很容易被 App 重启冲掉，
                // 而这里是「结果明明跑出来了却没进会话」的唯一线索。
                val detail = buildString {
                    appendLine("${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date(now))}")
                    appendLine("${error.javaClass.name}: ${error.message}")
                    error.stackTrace.take(6).forEach { appendLine("  at $it") }
                }
                runCatching {
                    val f = java.io.File(java.io.File(context.filesDir, "autotask"), "persist_error.log")
                    f.parentFile?.mkdirs()
                    f.appendText(detail + "\n")
                }
                AndroidAgentLogger.warnThrottled("auto_task_persist_failed") { "写回会话失败: ${error.message}" }
            }

        // 一次性任务（schedule=none 或 after）执行完即结束：active=false 让列表显示"已执行"。
        // 只有 every/daily 才保持 active。
        //
        // **这里曾经写反成 `!= false`**：schedule=null 时 `null != false` 为 true，
        // 于是一次性任务永远保持活跃；再叠加「无时间限制 → isDue 恒 true」，
        // 结果每 60 秒被重复触发一次。真机实测 25 秒内触发两次才暴露。
        val offlineRunCount = task.runCount + 1
        val stillActive = task.schedule?.repeating == true && offlineRunCount < task.maxRuns
        val stored = if (result.length > MAX_RESULT_CHARS) {
            result.take(MAX_RESULT_CHARS) + "\n…（已截断）"
        } else {
            result
        }
        store.saveBlocking(
            task.copy(
                lastRunAt = now,
                lastResult = stored,
                runCount = offlineRunCount,
                active = stillActive,
            ),
        )

        // **必须重新导出 pending.txt**：脚本读的是「下次触发的绝对时间戳」，
        // 而它是按 lastRunAt 算的。执行完不重导出，脚本仍拿着旧的（已过去的）时间戳，
        // 于是每分钟都会判定「到点」——重复任务会被反复触发、反复花钱。
        // 这一步是「执行完 → 下次何时再跑」的唯一连接点。
        store.exportTo(context)
        AutoTaskNotifier.notifyFinished(context, task, result)
        AutoTaskExport.clearInProgress(context, task.id)
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

        // 轮数护栏：complete() 没有 maxRounds 参数，靠监听 RoundStarted 自己掐。
        // 之前 buildPrompt 里写「最多执行 N 轮」只是提示词，模型完全可以无视 ——
        // 成本不可控是自动任务最危险的失手方式（无人看着还可能一直跑）。
        val controller = AgentRunController()
        var rounds = 0
        val reply = AgentModelClient.complete(
            config = config,
            prompt = buildPrompt(task),
            toolExecutor = tools,
            provider = provider,
            runController = controller,
            history = history,
            sessionId = targetConversationId,
            onEvent = { event ->
                if (event is io.github.mangi.eta.agent.runtime.AgentEvent.RoundStarted) {
                    rounds += 1
                    if (rounds > task.maxRounds) {
                        // 首次超限：不直接砍，而是注入「请收尾」让它基于已有信息给结论。
                        // 直接 cancel 会让整轮返回 "Agent run cancelled"，用户拿到的是失败而非结果
                        // —— 真机实测发现的（maxRounds=1 时必然踩到）。
                        if (rounds <= task.maxRounds + 2) {
                            AndroidAgentLogger.warnThrottled("auto_task_round_cap") {
                                "自动任务 ${task.id} 达轮数上限 ${task.maxRounds}，请求收尾（第 $rounds 轮）"
                            }
                            controller.steer(
                                "已达本次自动任务的轮数上限。请立即基于已获得的信息给出结论，" +
                                    "不要再调用任何工具。",
                            )
                        } else {
                            // 兜底：连续两轮仍不肯收尾，强制中断（防止无人值守时无限烧钱）
                            AndroidAgentLogger.warnThrottled("auto_task_round_hard_stop") {
                                "自动任务 ${task.id} 超限 ${rounds - task.maxRounds} 轮仍不收尾，强制中断"
                            }
                            controller.cancel()
                        }
                    }
                }
            },
        )

        return reply.content.ifBlank { "（模型未返回内容）" }
    }

    /**
     * 读会话历史（historyJson → ConversationMessage 列表）。
     *
     * **必须优先读 checkpoint**：它才是「发给模型的历史」，与正常对话同源。
     * 之前读 `conversations.history_json`（真实数据里是 `[]`）→ 模型零上下文、
     * 前缀与主路径不一致 → 缓存 100% 击穿。真机排查时才发现。
     *
     * **不再做字符截断**：截断会让前缀与正常对话不一致，同样击穿缓存。
     * checkpoint 本身已经是压缩后的合理大小，与正常一轮请求同量级。
     */
    private suspend fun loadHistory(
        dao: io.github.mangi.eta.data.db.ConversationDao,
        conversationId: String,
    ): List<AgentModelClient.ConversationMessage> = runCatching {
        val checkpointJson = runCatching { dao.contextCheckpoint(conversationId)?.historyJson }.getOrNull()
        val conversationJson = runCatching { dao.historyJsonFor(conversationId) }.getOrNull()
        val raw = AutoTaskHistorySource.pick(checkpointJson, conversationJson)
            ?: return@runCatching emptyList()
        io.github.mangi.eta.agent.model.AgentConversationCodec.decodeTranscript(raw)
    }.getOrElse { emptyList() }

    private companion object {
        /** 进程级互斥：跨 SimpleAutoTaskRuntime 实例共享。 */
        private val RUN_LOCK = Any()

        /** 写入数据库与通知的结果上限，避免模型长输出把库撑大。 */
        const val MAX_RESULT_CHARS = 8_000


    }

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
        // ID 里带 taskId：同一毫秒内不同任务落结果时不会互相覆盖（@Upsert 会静默替换）
        val uid = "${task.id}-$now"
        dao.appendAutoTaskRound(
            conversationId = conversationId,
            userMessage = io.github.mangi.eta.data.db.ConversationMessageEntity(
                id = "auto-user-$uid",
                conversationId = conversationId,
                sortIndex = 0,
                type = "user",
                content = "⚡ [自动任务] ${task.title}（${task.condition.describe()}）",
            ),
            assistantMessage = io.github.mangi.eta.data.db.ConversationMessageEntity(
                id = "auto-assistant-$uid",
                conversationId = conversationId,
                sortIndex = 0,
                type = "assistant",
                content = result,
            ),
        )
    }
}