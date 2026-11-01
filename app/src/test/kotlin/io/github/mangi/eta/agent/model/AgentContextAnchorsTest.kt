package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentContextAnchorsTest {

    private val card = """
## 目标
修个 bug
## 用户约束
不许删数据库
不许改生产配置
## 已完成
改了两处
## 未完成
还剩测试
## 关键标识
包名 com.foo / 路径 /workspace/x
## 待确认
无
## 环境快照
root
""".trimIndent()

    @Test
    fun extractsConstraintAndIdentifierSectionsVerbatim() {
        val anchors = AgentContextAnchors.extract(card)
        assertTrue(anchors.contains("不许删数据库"))
        assertTrue(anchors.contains("包名 com.foo / 路径 /workspace/x"))
        assertFalse("不应包含已完成段", anchors.contains("改了两处"))
    }

    @Test
    fun returnsEmptyForBlankCard() {
        assertEquals("", AgentContextAnchors.extract(""))
        assertEquals("", AgentContextAnchors.extract("   \n"))
    }

    @Test
    fun returnsEmptyWhenHeadersMissing() {
        assertEquals("", AgentContextAnchors.extract("## 目标\n只有目标"))
    }

    @Test
    fun generationCountsPriorSummaries() {
        fun summary() = JSONObject().put("role", "assistant").put("content", "摘要").put("_eta_context_summary", true)
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", "s"))
        assertEquals(1, AgentContextAnchors.generationOf(messages, 1))
        messages.put(summary())
        assertEquals(2, AgentContextAnchors.generationOf(messages, 1))
        messages.put(summary())
        assertEquals(3, AgentContextAnchors.generationOf(messages, 1))
    }

    @Test
    fun sectionStopsAtNextHeader() {
        val anchors = AgentContextAnchors.extract(card)
        // 关键标识段在「## 待确认」前结束
        assertFalse(anchors.contains("## 待确认"))
    }
}
