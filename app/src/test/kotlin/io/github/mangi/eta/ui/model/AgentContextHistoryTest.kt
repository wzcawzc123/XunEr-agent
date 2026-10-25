package io.github.mangi.eta.ui.model

import io.github.mangi.eta.agent.model.AgentConversationCodec
import io.github.mangi.eta.agent.model.AgentModelClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentContextHistoryTest {

    private fun summary(
        content: String,
        turns: Int = 3,
        meta: String = "",
    ) = AgentModelClient.ConversationMessage(
        role = "assistant",
        content = content,
        contextSummary = true,
        compactedUserTurns = turns,
        compactionMeta = meta,
    )

    @Test
    fun `投影提取序号下标与元数据`() {
        val history = listOf(
            AgentModelClient.ConversationMessage("user", "开始"),
            summary("摘要一", turns = 2, meta = """{"at":1730000000000,"tokensBefore":480000,"covered":2,"dropped":9}"""),
            AgentModelClient.ConversationMessage("user", "继续"),
            summary("摘要二", turns = 5, meta = ""),
        )
        val records = AgentContextHistory.records(history)
        assertEquals(2, records.size)
        assertEquals(1, records[0].ordinal)
        assertEquals(1, records[0].historyIndex)
        assertEquals(480000, records[0].tokensBefore)
        assertEquals(9, records[0].droppedMessages)
        assertEquals(1730000000000L, records[0].atMillis)
        assertEquals("摘要二", records[1].summaryText)
        assertEquals(2, records[1].ordinal)
        assertEquals(3, records[1].historyIndex)
        assertNull("旧数据无元数据应显示未记录", records[1].tokensBefore)
        assertNull(records[1].atMillis)
    }

    @Test
    fun `没有摘要时投影为空`() {
        assertTrue(AgentContextHistory.records(listOf(AgentModelClient.ConversationMessage("user", "你好"))).isEmpty())
    }

    @Test
    fun `元数据随摘要往返序列化`() {
        val meta = """{"at":1730000000000,"tokensBefore":480000,"covered":2,"dropped":9}"""
        val message = summary("摘要", meta = meta)
        val json = AgentConversationCodec.toJsonObject(message)
        val back = AgentConversationCodec.fromJsonObject(json)
        assertEquals(meta, back.compactionMeta)
        assertTrue(back.contextSummary)
    }

    @Test
    fun `非法元数据不炸并按未记录处理`() {
        val records = AgentContextHistory.records(listOf(summary("摘要", meta = "{broken")))
        assertEquals(1, records.size)
        assertNull(records[0].tokensBefore)
    }
}
