package io.github.mangi.eta.ui.app

import io.github.mangi.eta.data.db.ConversationMessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归测试：检查点缺失时的兜底 history 必须**有界**。
 *
 * 背景（真机实证）：早期实现直接返回全部 user/assistant 正文，长会话可达 800 万字符，
 * 使下一次 run 的 `messages` 远超模型窗口 —— 请求被 Provider 拒绝（`model_retry` /
 * `runtime_failed`），随后压缩才压回来，表现为"频繁压缩 + 运行失败"成对出现。
 * 实测 before 估算高达 3,278,699（对应约 800 万字符），而正常值只有 84,377。
 */
internal class LegacyHistoryBoundsTest {
    private fun msg(index: Int, type: String, content: String) = ConversationMessageEntity(
        id = "m$index",
        conversationId = "conv-test",
        sortIndex = index,
        type = type,
        content = content,
    )

    private fun historyOf(list: List<ConversationMessageEntity>) =
        AgentConversationStore.run { list.toLegacyHistory() }

    @Test
    fun messageCountIsCapped() {
        // 100 条短消息：只应保留最近 40 条
        val list = (0 until 100).map { msg(it, "user", "u$it") }
        val history = historyOf(list)

        assertEquals(40, history.size)
        // 保留的应是最新的那批，且时间朝序
        assertEquals("u60", history.first().content)
        assertEquals("u99", history.last().content)
    }

    @Test
    fun charBudgetIsCapped() {
        // 每条 5,000 字符：32,000 上限下最多 6 条
        val big = "x".repeat(5_000)
        val list = (0 until 50).map { msg(it, "assistant", "$big-$it") }
        val history = historyOf(list)

        val total = history.sumOf { it.content.length }
        assertTrue("字符总量必须受 32,000 约束，实际 $total", total <= 32_000)
        assertEquals(6, history.size)
        // 仍然从最新往回取
        assertTrue(history.last().content.endsWith("-49"))
    }

    @Test
    fun thinkingAndToolMessagesAreDropped() {
        val list = listOf(
            msg(0, "user", "hello"),
            msg(1, "thinking", "internal reasoning"),
            msg(2, "assistant", "hi"),
            msg(3, "tool", "tool payload"),
            msg(4, "system_notice", "runtime_failed"),
            msg(5, "assistant", "done"),
        )
        val history = historyOf(list)

        assertEquals(3, history.size)
        assertEquals(listOf("user", "assistant", "assistant"), history.map { it.role })
        assertEquals(listOf("hello", "hi", "done"), history.map { it.content })
    }

    @Test
    fun blankAssistantIsSkippedButUserKept() {
        val list = listOf(
            msg(0, "assistant", "   "),
            msg(1, "user", "q"),
        )
        val history = historyOf(list)

        // assistant 空白被过滤（与原实现一致），user 保留
        assertEquals(1, history.size)
        assertEquals("user", history.first().role)
    }

    @Test
    fun emptyInputYieldsEmptyHistory() {
        assertTrue(historyOf(emptyList()).isEmpty())
    }

    @Test
    fun longSessionNoLongerProducesHugeHistory() {
        // 模拟长会话：2,000 条、每条 3,000 字符 ⇒ 原实现会产生 600 万字符
        val body = "y".repeat(3_000)
        val list = (0 until 2_000).map { msg(it, "user", body) }
        val history = historyOf(list)

        val total = history.sumOf { it.content.length }
        assertTrue("不得再产生百万级字符的兜底历史，实际 $total", total <= 32_000)
        assertTrue(history.size <= 40)
    }
}
