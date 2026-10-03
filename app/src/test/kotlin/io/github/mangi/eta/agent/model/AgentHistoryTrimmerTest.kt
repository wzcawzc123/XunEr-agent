package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.model.AgentModelClient.ConversationMessage
import io.github.mangi.eta.agent.skill.SkillContext
import org.json.JSONArray
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
     * 决定性断言：即便 history 被撑到远超窗口，**装配出来的 messages 也必须落在窗口内**。
     * 修复前这里会得到约 2,000,000 的估算值——正是用户实测遇到的那类请求，
     * 它要么被服务商拒绝、要么让压缩做不完（摘要输入另有上限）而最终整任务失败。
     */
    @Test
    fun assembledMessagesStayWithinTheWindowEvenWhenHistoryIsOversized() {
        val oversized = longHistory(2_000)
        assertTrue(oversized.estimate() > WINDOW)

        val messages = AgentPromptBuilder.buildInitialMessages(
            config = AgentModelClient.ModelConfig(
                baseUrl = "https://example.invalid/v1",
                apiKey = "test-key",
                model = "test-model",
                contextWindow = WINDOW,
                systemPrompt = "你是 Eta。",
            ),
            prompt = "继续",
            images = emptyList(),
            history = oversized,
            skillContext = SkillContext.EMPTY,
        )

        val estimate = AgentContextBudget.rawEstimate(messages)
        assertTrue("装配后的上下文不得超出窗口，实际 $estimate", estimate < WINDOW)
        // 尾部必须保留：最后一条是本次的用户提问。
        assertTrue(messages.getJSONObject(messages.length() - 1).optString("role") == "user")
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
