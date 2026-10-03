package io.github.mangi.eta.agent.model

import org.json.JSONArray

/**
 * 装配 messages 之前的**窗口级兜底裁剪**。
 *
 * 真机实证（2026-10-03，`docs` 外报告《Eta-上下文压缩-真机数据追查》）：
 * 会话的 `history` 会逐轮吃下整条 transcript（含全部工具结果），而清空只发生在
 * **压缩成功之后**；一旦压缩失败或历史被异常放大，下一次 run 就会带着远超窗口的
 * 上下文发出。此时压缩自身也做不完（摘要输入另有上限），于是落进
 * `CONTEXT_NO_REDUCTION`，而 `AgentContextSession` 对"超过窗口时的压缩失败"会直接抛出
 * —— 界面表现为「Runtime 运行失败」，整个任务作废（用户实测遇到过约 3,278,699 的估算值）。
 *
 * 这里只做**最后一道保险**：只有当历史本身快要顶满窗口时才从最旧端裁剪；
 * 正常规模的历史一律原样返回，不改变既有行为，也不参与任何摘要决策。
 *
 * 注意：裁剪只影响**本次发给模型的上下文**，不改动持久化的 `history`/`journal`，
 * 因此用户侧记录不会丢失，只是模型看不到最旧的一段。
 */
internal object AgentHistoryTrimmer {

    /** 裁剪后至少要落在这个窗口比例以内，给系统提示、工具 schema 与本轮增长留出余量。 */
    const val TARGET_RATIO = 0.80

    /** 工具 schema 的保守预留（实测 82 个工具约 15k token，这里按更多工具留量）。 */
    const val TOOL_SCHEMA_RESERVE_TOKENS = 24_000

    /** 空数组在 rawEstimate 口径下的固定开销，用于求单条消息的净成本。 */
    private val EMPTY_ESTIMATE = AgentContextBudget.rawEstimate(JSONArray())

    data class Outcome(
        val messages: List<AgentModelClient.ConversationMessage>,
        val droppedMessages: Int,
    ) {
        val trimmed: Boolean get() = droppedMessages > 0
    }

    fun trim(
        history: List<AgentModelClient.ConversationMessage>,
        window: Int?,
        systemEstimate: Int = 0,
    ): Outcome {
        val safeWindow = window?.takeIf { it > 0 } ?: return Outcome(history, 0)
        val limit = (safeWindow * TARGET_RATIO).toInt() - systemEstimate - TOOL_SCHEMA_RESERVE_TOKENS
        // 窗口小到连系统提示都装不下：此时任何历史都只会让请求必然失败，只能不带历史。
        if (limit <= 0) return Outcome(emptyList(), history.size)

        val costs = history.map(::costOf)
        // 与 rawEstimate 同口径：固定开销 + 逐条成本，这样"裁剪后 ≤ limit"是精确不变量。
        var total = EMPTY_ESTIMATE + costs.sum()
        if (total <= limit) return Outcome(history, 0)

        var start = 0
        while (start < history.size && total > limit) {
            total -= costs[start]
            start++
        }
        // 裁剪后首条不能是孤立的 tool 结果——它的 tool_calls 已被裁掉，
        // 多数服务商会直接以 400 拒绝这样的消息序列。
        while (start < history.size && history[start].role == ROLE_TOOL) start++
        if (start >= history.size) return Outcome(emptyList(), history.size)
        return Outcome(history.subList(start, history.size), start)
    }

    private fun costOf(message: AgentModelClient.ConversationMessage): Int {
        val json = runCatching { AgentConversationCodec.toJsonObject(message) }.getOrNull() ?: return 0
        return (AgentContextBudget.rawEstimate(JSONArray().put(json)) - EMPTY_ESTIMATE).coerceAtLeast(0)
    }

    private const val ROLE_TOOL = "tool"
}
