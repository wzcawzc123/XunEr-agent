package io.github.mangi.eta.agent.autotask

import io.github.mangi.eta.core.AndroidAgentLogger

/**
 * 自动任务的**投递器**：把到点的任务变成「会话里的一轮正常对话」。
 *
 * ## 为什么必须有这一层
 *
 * 参考 DeepSeek Harness 的实现（`packages/schedule/schedule/src/runtime.ts:123`）：
 * ```
 * const resolved = await this.ctx.sessionController.resolveAgent(task.sessionId)
 * // followup synchronously appends the inbox splice before flush observes the Session.
 * resolved.agent.followup(message)
 * ```
 * 它是把消息**投进会话的 inbox**，由该会话的正常流程生成一轮 ——
 * 而不是绕过会话自己跑一次模型调用。
 *
 * 我第一版做错的地方：直接在后台调 `AgentModelClient.complete()` 然后手写两条消息进库。
 * 结果数据进了库，但会话状态/UI 完全不知道 —— 界面不刷新，等于「往历史里塞记录」。
 *
 * ## 本类的职责
 *
 * UI 层（[io.github.mangi.eta.ui.app.AgentAppState]）在存活时把自己「发起一轮对话」
 * 的能力注册进来；后台入口（AutoTaskService）优先走这里。
 * 拿不到（App 不在前台 / 正在跑别的轮次）时由调用方降级为离线投递。
 */
internal object AutoTaskDispatcher {

    /**
     * UI 注册的投递入口。
     *
     * 返回 true 表示**已经发起一轮正常对话**（后续落库、刷新、生命周期都由正常流程负责）。
     * 返回 false 表示当前不可投递（目标不是当前会话 / 有轮次在跑）。
     */
    @Volatile
    private var deliver: ((conversationId: String, prompt: String) -> Boolean)? = null

    /** 由 UI 层在存活期间注册；传 null 表示卸载（例如 Activity 销毁）。 */
    fun register(handler: ((conversationId: String, prompt: String) -> Boolean)?) {
        deliver = handler
    }

    /** UI 是否在场（决定「排队等轮次结束」还是「立刻降级离线执行」）。 */
    fun hasHandler(): Boolean = deliver != null

    /**
     * 待投递队列。
     *
     * 为什么需要：后台任务到点时，用户可能**正在跟 Agent 对话**（`currentRunId != null`）。
     * 这时不能打断，也不能直接降级（那样界面看不到）。
     * 正确做法是排队，等这一轮结束再投递 —— 也就是「轮次结束再发起新轮次」。
     */
    private val pending = java.util.concurrent.ConcurrentLinkedQueue<Pair<String, String>>()

    /** 有上限：后台反复到点时避免无限堆积（正常情况同一任务只会入队一次）。 */
    private const val MAX_PENDING = 20

    fun enqueue(conversationId: String, prompt: String) {
        if (pending.size >= MAX_PENDING) {
            AndroidAgentLogger.warnThrottled("auto_task_queue_full") { "待投递队列已满，丢弃最旧的一条" }
            pending.poll()
        }
        pending.add(conversationId to prompt)
    }

    /**
     * 轮次结束时调用：把排队中的任务投递出去。
     *
     * 只投递队首且成功才出队 —— 失败（仍然忙/条件不满足）就保留，等下一次结束再试。
     */
    fun flushPending() {
        val handler = deliver ?: return
        while (true) {
            val item = pending.peek() ?: return
            val ok = runCatching { handler(item.first, item.second) }.getOrDefault(false)
            if (!ok) return
            pending.poll()
            AndroidAgentLogger.info("排队任务已投递: ${item.first}")
        }
    }

    /**
     * 尝试把任务投递成会话里的一轮对话。
     *
     * @return true = 已交给正常对话流程；false = 调用方应走离线降级
     */
    fun tryDeliver(conversationId: String?, prompt: String): Boolean {
        if (conversationId.isNullOrBlank()) return false
        val handler = deliver ?: return false
        return runCatching { handler(conversationId, prompt) }
            .onFailure {
                AndroidAgentLogger.warnThrottled("auto_task_dispatch_failed") {
                    "投递到会话失败（将降级为离线投递）: ${it.javaClass.simpleName}: ${it.message}"
                }
            }
            .getOrDefault(false)
    }
}
