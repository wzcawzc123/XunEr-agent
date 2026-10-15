package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.model.AgentModelClient.ConversationMessage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 真机环境难以稳定触发"上下文膨胀到远超窗口"（需要累积到 85 万并恰好压缩失败），
 * 因此这里**直接构造该条件**：造出一份估算远超窗口的 history，
 * 断言装配前会被裁回预算内——这正是用户遇到「约 3,278,699 → Runtime 运行失败」的根因条件。
 */
class AgentHistoryTrimmerTest {

    @Test
    fun historyWithinBudgetIsReturnedUntouched() {
        val history = listOf(
            ConversationMessage(role = "user", content = "你好"),
            ConversationMessage(role = "assistant", content = "你好，有什么可以帮你的？"),
        )
        val outcome = AgentHistoryTrimmer.trim(history, window = WINDOW)
        assertSame("正常规模的历史必须原样返回", history, outcome.messages)
        assertFalse(outcome.trimmed)
        assertEquals(0, outcome.droppedMessages)
    }

    @Test
    fun unknownOrInvalidWindowLeavesHistoryUntouched() {
        val history = longHistory(400)
        assertSame(history, AgentHistoryTrimmer.trim(history, window = null).messages)
        assertSame(history, AgentHistoryTrimmer.trim(history, window = 0).messages)
    }

    @Test
    fun oversizedHistoryIsTrimmedIntoTheWindowBudgetKeepingTheNewestMessages() {
        val history = longHistory(2_000)
        assertTrue("构造的历史必须远超窗口，否则本测试没有意义", history.estimate() > budget(WINDOW))

        val outcome = AgentHistoryTrimmer.trim(history, window = WINDOW)

        assertTrue(outcome.trimmed)
        assertTrue(outcome.messages.size < history.size)
        // 精确不变量：裁剪后必须落在预算内，否则请求照样会超窗。
        assertTrue(outcome.messages.estimate() <= budget(WINDOW))
        assertEquals(history.size - outcome.droppedMessages, outcome.messages.size)
        // 保留的是"最近"的一段，不是最早的一段。
        assertEquals(history.last(), outcome.messages.last())
    }

    @Test
    fun trimmingNeverStartsWithAnOrphanToolResult() {
        val roles = listOf("user", "assistant", "tool", "assistant", "tool")
        val history = List(1_500) { index ->
            ConversationMessage(role = roles[index % roles.size], content = "数据".repeat(200))
        }

        var trimmedAtLeastOnce = false
        for (window in listOf(WINDOW, WINDOW / 2, WINDOW / 4)) {
            val outcome = AgentHistoryTrimmer.trim(history, window = window)
            if (!outcome.trimmed) continue
            trimmedAtLeastOnce = true
            assertNotEquals(
                "裁剪后首条不能是孤立 tool 结果（tool_calls 已被裁掉，服务商会 400）",
                "tool",
                outcome.messages.first().role,
            )
            assertTrue(outcome.messages.estimate() <= budget(window))
        }
        assertTrue("至少应有一个窗口触发裁剪", trimmedAtLeastOnce)
    }

    @Test
    fun systemPromptEstimateShrinksTheHistoryBudget() {
        val history = longHistory(2_000)
        val withoutSystem = AgentHistoryTrimmer.trim(history, window = WINDOW)
        val withSystem = AgentHistoryTrimmer.trim(history, window = WINDOW, systemEstimate = 120_000)

        assertTrue(
            "系统提示占用越大，能留给历史的部分应越少",
            withSystem.droppedMessages > withoutSystem.droppedMessages,
        )
        assertTrue(withSystem.messages.estimate() <= budget(WINDOW, systemEstimate = 120_000))
    }

    @Test
    fun tinyWindowYieldsNoHistoryInsteadOfAnImpossibleRequest() {
        val history = longHistory(50)
        val outcome = AgentHistoryTrimmer.trim(history, window = 10_000, systemEstimate = 50_000)
        assertTrue("预算为负时宁可不带历史，也不发一个必然失败的请求", outcome.messages.isEmpty())
        assertEquals(50, outcome.droppedMessages)
    }

    /**
     * 决定性断言：即便 history 被撑到远超窗口，**裁剪后的 messages 也必须落在窗口内**。
     * 修复前这里会得到约 2,000,000 的估算值——正是用户实测遇到的那类请求，
     * 它要么被服务商拒绝、要么让压缩做不完（摘要输入另有上限）而最终整任务失败。
     */
    @Test
    fun trimmedMessagesStayWithinTheWindowEvenWhenHistoryIsOversized() {
        val oversized = longHistory(2_000)
        assertTrue(oversized.estimate() > WINDOW)

        val systemEstimate = AgentContextBudget.rawEstimate(
            JSONArray().put(AgentConversationCodec.userTextMessage("你是 Eta。")),
            JSONArray(),
        )
        val outcome = AgentHistoryTrimmer.trim(oversized, WINDOW, systemEstimate)

        assertTrue("超大历史必须被裁剪", outcome.trimmed)
        val trimmedArray = JSONArray()
        outcome.messages.forEach { trimmedArray.put(AgentConversationCodec.toJsonObject(it)) }
        val estimate = AgentContextBudget.rawEstimate(trimmedArray)
        assertTrue("裁剪后的历史不得超出窗口预算，实际 $estimate", estimate < WINDOW)
        // 尾部必须保留：最新消息仍在。
        assertEquals(oversized.last(), outcome.messages.last())
    }

    // ---- H1（v3.11.24）：trimJson 保留 _eta_* 瞬态键，签名回合不被裁剪剥掉 ----

    @Test
    fun trimJsonPreservesEphemeralKeysInsteadOfDroppingThem() {
        // 长历史触发裁剪；最近保留段内携带三类瞬态键，裁剪后必须原样保留
        //（否则从 JSONObject 往返重建会剥掉它们，签名轮随后每轮 400）。
        val thinkingAssistant = JSONObject()
            .put("role", "assistant")
            .put("content", "正在处理")
            .put("tool_calls", JSONArray().put(JSONObject()
                .put("id", "toolu_1").put("type", "function")
                .put("function", JSONObject().put("name", "fixture").put("arguments", "{}"))))
        AnthropicEphemeralState.attachContentBlocks(thinkingAssistant, JSONArray()
            .put(JSONObject().put("type", "thinking").put("signature", "EPHEMERAL_SIG")))
        val observation = JSONObject()
            .put("role", "user")
            .put("content", "[屏幕观察]")
            .put("_eta_observation", true)
        val screenContext = JSONObject()
            .put("role", "assistant")
            .put("content", "屏幕上下文")
            .put("_eta_assistant_screen_context", true)
        val history = longJsonHistory(2_000) + listOf(observation, thinkingAssistant, screenContext)

        val outcome = AgentHistoryTrimmer.trimJson(history, WINDOW)
        assertTrue("超大历史必须被裁剪", outcome.trimmed)
        assertTrue("思考签名块键必须被保留", outcome.messages.toString().contains("EPHEMERAL_SIG"))
        assertTrue("观察标记键必须被保留", outcome.messages.toString().contains("_eta_observation"))
        assertTrue("屏幕上下文键必须被保留", outcome.messages.toString().contains("_eta_assistant_screen_context"))
    }

    @Test
    fun trimJsonKeepsSignedAnthropicRoundIntactWhenOnlyRecentMessagesAreRetained() {
        // 裁剪保留最近段时，若最近段内还有未回传结果的签名工具轮，
        // 签名块必须原样保留（否则后续每轮 400）。
        val pendingAssistant = JSONObject()
            .put("role", "assistant")
            .put("content", "")
            .put("tool_calls", JSONArray().put(JSONObject()
                .put("id", "toolu_pending").put("type", "function")
                .put("function", JSONObject().put("name", "fixture").put("arguments", "{}"))))
        AnthropicEphemeralState.attachContentBlocks(pendingAssistant, JSONArray()
            .put(JSONObject().put("type", "thinking").put("signature", "PENDING_SIG")))
        val history = longJsonHistory(2_000) + pendingAssistant

        val outcome = AgentHistoryTrimmer.trimJson(history, WINDOW)
        assertTrue(outcome.trimmed)
        // 最近段保留的是尾部，pending 轮就在尾部。
        assertTrue(outcome.messages.last() === pendingAssistant)
        assertTrue(outcome.messages.toString().contains("PENDING_SIG"))
    }

    // ---- H2（v3.11.24）：校准口径统一 —— cal<1 不超裁、cal>1 不落入 CONTEXT_EXHAUSTED 死角 ----

    @Test
    fun trimWithLowCalibrationDoesNotOverTrimBelowTheWindowBudget() {
        // cal=0.25 时同样的 raw 历史校准占用为 1/4，必须用更大的历史确保触发裁剪。
        val history = longHistory(8_000)
        val lowCal = 0.25

        val outcome = AgentHistoryTrimmer.trim(history, WINDOW, calibration = lowCal)

        assertTrue(outcome.trimmed)
        val raw = rawEstimateOf(outcome.messages)
        // 裁剪后校准口径必须仍落在窗口预算内（0.95W − 工具预留）。
        val calibrated = raw * lowCal
        assertTrue(
            "cal=0.25 时裁剪后校准占用 $calibrated 不应低于预算下限（过度裁剪）",
            calibrated >= budget(WINDOW) * 0.5,
        )
        assertTrue("裁剪后校准占用 $calibrated 不得超出预算", calibrated <= budget(WINDOW) + 512)
    }

    @Test
    fun trimWithHighCalibrationStillLandsWithinTheWindowBudget() {
        val history = longHistory(8_000)
        val highCal = 2.0

        val outcome = AgentHistoryTrimmer.trim(history, WINDOW, calibration = highCal)

        assertTrue(outcome.trimmed)
        val raw = rawEstimateOf(outcome.messages)
        val calibrated = raw * highCal
        assertTrue(
            "cal=2.0 时裁剪后校准占用 $calibrated 必须落在窗口预算内，否则 CONTEXT_EXHAUSTED 必死",
            calibrated <= budget(WINDOW) + 512,
        )
        assertTrue("高估模型裁剪后应保留有效历史（不是空）", outcome.messages.isNotEmpty())
    }

    @Test
    fun trimWithLowCalibrationKeepsMoreHistoryThanUntrimmedComparison() {
        // 低校准（raw 高估）时，同一条消息的校准占用更小，
        // 裁剪线应换算后保留更多历史，而不是把窗口的 95% 都丢掉。
        val history = longHistory(8_000)
        val calLow = AgentHistoryTrimmer.trim(history, WINDOW, calibration = 0.25)
        val calOne = AgentHistoryTrimmer.trim(history, WINDOW, calibration = 1.0)
        assertTrue(calLow.trimmed && calOne.trimmed)
        assertTrue(
            "cal=0.25 应比 cal=1.0 保留更多历史（当前 calLow=${calLow.messages.size} calOne=${calOne.messages.size}）",
            calLow.messages.size > calOne.messages.size,
        )
    }

    private fun rawEstimateOf(messages: List<ConversationMessage>): Double {
        val array = JSONArray()
        messages.forEach { array.put(AgentConversationCodec.toJsonObject(it)) }
        return AgentContextBudget.rawEstimate(array).toDouble()
    }

    private fun longJsonHistory(count: Int): List<JSONObject> =
        List(count) { index ->
            JSONObject()
                .put("role", if (index % 2 == 0) "user" else "assistant")
                .put("content", "这是第 $index 条消息，用来把历史撑到窗口以上。" + "内容".repeat(300))
        }

    private fun longHistory(count: Int): List<ConversationMessage> =
        List(count) { index ->
            ConversationMessage(
                role = if (index % 2 == 0) "user" else "assistant",
                content = "这是第 $index 条消息，用来把历史撑到窗口以上。" + "内容".repeat(300),
            )
        }

    private fun List<ConversationMessage>.estimate(): Int {
        val array = JSONArray()
        forEach { array.put(AgentConversationCodec.toJsonObject(it)) }
        return AgentContextBudget.rawEstimate(array)
    }

    /** 与 AgentHistoryTrimmer 内部预算同式，用于断言"裁到了预算内"。 */
    private fun budget(window: Int, systemEstimate: Int = 0): Int =
        (window * AgentHistoryTrimmer.TARGET_RATIO).toInt() -
            systemEstimate - AgentHistoryTrimmer.TOOL_SCHEMA_RESERVE_TOKENS

    private companion object {
        const val WINDOW = 1_000_000
    }
}
