package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

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
 * 本次 run 的 `messages` 数组会被就地替换为裁剪结果，后续轮次与终态快照基于裁剪后历史；
 * 恢复/续接时仍从完整持久历史 + transcript 重建，故裁剪是逐 run 生效的最后一道保险。
 *
 * 口径契约（体检 H2，v3.11.24）：
 * - `limit` 用**窗口单位**（`window × TARGET_RATIO − systemEstimate − 工具预留`），
 *   `systemEstimate` 由调用方传入**校准后**的值；
 * - 内部 `costs`/`EMPTY_ESTIMATE` 是 **raw 估算**，比较前统一乘以 [calibration]
 *   换算到窗口单位——避免 raw（本地估算）与窗口（实际 token）两种尺度混比，
 *   否则 cal<1（raw 高估）时会过度裁剪、cal>1 时裁剪后仍超窗触发 CONTEXT_EXHAUSTED。
 */
internal object AgentHistoryTrimmer {

    /**
     * 裁剪后至少要落在这个窗口比例以内。
     * 这个值必须明显高于 [AgentContextBudget.TRIGGER_RATIO]，让正常的摘要压缩机制先有机会工作；
     * 它只作为“摘要也救不回来”时的最后一道硬保险。
     */
    const val TARGET_RATIO = 0.95

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

    data class JsonOutcome(
        val messages: List<JSONObject>,
        val droppedMessages: Int,
    ) {
        val trimmed: Boolean get() = droppedMessages > 0
    }

    fun trim(
        history: List<AgentModelClient.ConversationMessage>,
        window: Int?,
        systemEstimate: Int = 0,
        calibration: Double = 1.0,
    ): Outcome {
        val jsonOutcome = trimJson(
            history = history.map(AgentConversationCodec::toJsonObject),
            window = window,
            systemEstimate = systemEstimate,
            calibration = calibration,
        )
        // 未裁剪时保持原引用（既有契约）；裁剪时才转换回 DTO。
        if (!jsonOutcome.trimmed) return Outcome(history, 0)
        return Outcome(
            messages = jsonOutcome.messages.map(AgentConversationCodec::fromJsonObject),
            droppedMessages = jsonOutcome.droppedMessages,
        )
    }

    /**
     * JSON 形态的裁剪：**保留原始 `_eta_*` 瞬态键**（思考签名、观察标记、屏幕上下文），
     * 不做 `ConversationMessage` 往返重建——压缩路径已有签名守卫，裁剪路径必须对等，
     * 否则签名回合内裁剪会剥掉 `_eta_anthropic_content_blocks` 导致后续每轮 400。
     */
    fun trimJson(
        history: List<JSONObject>,
        window: Int?,
        systemEstimate: Int = 0,
        calibration: Double = 1.0,
    ): JsonOutcome {
        val safeWindow = window?.takeIf { it > 0 } ?: return JsonOutcome(history, 0)
        val limit = (safeWindow * TARGET_RATIO).toInt() - systemEstimate - TOOL_SCHEMA_RESERVE_TOKENS
        // 窗口小到连系统提示都装不下：此时任何历史都只会让请求必然失败，只能不带历史。
        if (limit <= 0) return JsonOutcome(emptyList(), history.size)

        val costs = history.map(::costOfJson)
        // 固定开销与逐条成本都是 raw 估算，统一乘 calibration 换算到窗口单位后与 limit 比较。
        // Double 累计：逐条 toLong 会引入截断误差，导致裁剪停得过早（cal<1 时误差被放大）。
        var total = EMPTY_ESTIMATE * calibration + costs.sumOf { it * calibration }
        if (total <= limit) return JsonOutcome(history, 0)

        var start = 0
        while (start < history.size && total > limit) {
            total -= costs[start] * calibration
            start++
        }
        // 裁剪后首条不能是孤立的 tool 结果——它的 tool_calls 已被裁掉，
        // 多数服务商会直接以 400 拒绝这样的消息序列。
        while (start < history.size && history[start].optString("role") == ROLE_TOOL) start++
        if (start >= history.size) return JsonOutcome(emptyList(), history.size)
        return JsonOutcome(history.subList(start, history.size), start)
    }

    private fun costOf(message: AgentModelClient.ConversationMessage): Int {
        val json = runCatching { AgentConversationCodec.toJsonObject(message) }.getOrNull() ?: return 0
        return costOfJson(json)
    }

    private fun costOfJson(message: JSONObject): Int =
        (AgentContextBudget.rawEstimate(JSONArray().put(message)) - EMPTY_ESTIMATE).coerceAtLeast(0)

    private const val ROLE_TOOL = "tool"
}
