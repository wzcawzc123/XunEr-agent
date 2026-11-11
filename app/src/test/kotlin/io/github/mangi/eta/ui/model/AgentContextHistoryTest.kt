package io.github.mangi.eta.ui.model

import io.github.mangi.eta.agent.model.AgentModelClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 压缩历史投影：只有摘要消息算一次压缩，顺序即代数。 */
class AgentContextHistoryTest {

    @Test
    fun noSummaryMeansNoRecords() {
        val history = listOf(
            message(role = "user", content = "开始"),
            message(role = "assistant", content = "好的"),
        )
        assertTrue(AgentContextHistory.records(history).isEmpty())
    }

    @Test
    fun summarisesCoverageInOrder() {
        val history = listOf(
            message(role = "user", content = "第一轮"),
            message("user", "第二轮"),
            summary(covered = 2, through = 3, text = "[Eta 上下文摘要] 卡 1"),
            message(role = "assistant", content = "继续"),
            summary(covered = 5, through = 6, text = "[Eta 上下文摘要] 卡 2"),
        )

        val records = AgentContextHistory.records(history)
        assertEquals(2, records.size)
        assertEquals(listOf(1, 2), records.map { it.ordinal })
        assertEquals(listOf(2, 4), records.map { it.historyIndex })
        assertEquals(listOf(2, 5), records.map { it.coveredUserTurns })
        assertEquals(listOf(3, 6), records.map { it.summaryThroughUserTurn })
        assertTrue(records[0].summaryText.contains("卡 1"))
        assertTrue(records[1].summaryText.contains("卡 2"))
    }

    @Test
    fun ordinaryAssistantMessagesAreNotCounted() {
        // contextSummary=false 的普通 assistant 消息（哪怕内容像摘要）不算压缩记录
        val history = listOf(
            message(role = "assistant", content = "[Eta 上下文摘要：看起来像但不是]"),
            summary(covered = 1, through = 2, text = "真正的卡"),
        )
        val records = AgentContextHistory.records(history)
        assertEquals(1, records.size)
        assertEquals(1, records.single().ordinal)
    }

    private fun message(role: String, content: String) = AgentModelClient.ConversationMessage(
        role = role,
        content = content,
    )

    private fun summary(covered: Int, through: Int, text: String) =
        AgentModelClient.ConversationMessage(
            role = "assistant",
            content = text,
            contextSummary = true,
            compactedUserTurns = covered,
            summaryThroughUserTurn = through,
        )
}
