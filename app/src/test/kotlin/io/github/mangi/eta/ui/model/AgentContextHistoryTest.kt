package io.github.mangi.eta.ui.model

import io.github.mangi.eta.agent.model.provider.AgentModelClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 压缩历史投影：摘要消息算一次压缩，归档号给出真实代数。 */
class AgentContextHistoryTest {

    @Test
    fun noSummaryMeansNoRecords() {
        val history = listOf(
            message(role = "user", content = "开始"),
            message(role = "assistant", content = "好的"),
        )
        assertTrue(AgentContextHistory.records(history).isEmpty())
        assertEquals(0, AgentContextHistory.total(AgentContextHistory.records(history)))
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
        // 没有归档号时退化按出现顺序计数，次数等于条数。
        assertEquals(2, AgentContextHistory.total(records))
    }

    @Test
    fun ordinalComesFromArchiveId() {
        val records = AgentContextHistory.records(
            listOf(
                summary(covered = 2, through = 3, text = "卡 3", archiveId = "fold-3"),
                summary(covered = 9, through = 10, text = "卡 5", archiveId = "fold-5"),
            ),
        )
        assertEquals(listOf(3, 5), records.map { it.ordinal })
        assertEquals(listOf("fold-3", "fold-5"), records.map { it.archiveId })
        assertEquals(5, AgentContextHistory.total(records))
    }

    /**
     * 真实场景：每次压缩都把旧卡折进新摘要，history 里只剩一张卡。
     * 条数是 1，但压过 5 次——次数必须取代数，不能被条数截断。
     */
    @Test
    fun totalCountsAbsorbedCards() {
        val records = AgentContextHistory.records(
            listOf(
                message(role = "user", content = "第 55 轮"),
                summary(covered = 60, through = 61, text = "最新卡", archiveId = "fold-5"),
                message(role = "assistant", content = "继续"),
            ),
        )
        assertEquals(1, records.size)
        assertEquals(5, records.single().ordinal)
        assertEquals(5, AgentContextHistory.total(records))
    }

    @Test
    fun unknownArchiveIdFallsBackToOrdinal() {
        val records = AgentContextHistory.records(
            listOf(
                summary(covered = 1, through = 2, text = "无归档号"),
                summary(covered = 2, through = 3, text = "坏归档号", archiveId = "fold-"),
            ),
        )
        assertEquals(listOf(1, 2), records.map { it.ordinal })
        assertEquals(2, AgentContextHistory.total(records))
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
        assertEquals(1, AgentContextHistory.total(records))
    }

    private fun message(role: String, content: String) = AgentModelClient.ConversationMessage(
        role = role,
        content = content,
    )

    private fun summary(
        covered: Int,
        through: Int,
        text: String,
        archiveId: String = "",
    ) = AgentModelClient.ConversationMessage(
        role = "assistant",
        content = text,
        contextSummary = true,
        compactedUserTurns = covered,
        summaryThroughUserTurn = through,
        compactionArchiveId = archiveId,
    )
}
