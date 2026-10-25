package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentContextImportanceTest {

    @Test
    fun `约束句与路径 ID 识别`() {
        assertTrue(AgentContextImportance.isConstraint("必须保留用户偏好设置"))
        assertTrue(AgentContextImportance.isConstraint("不要把剧情写成实际工具结果"))
        assertTrue(AgentContextImportance.isValuable("/data/app/src/main/AndroidManifest.xml"))
        assertTrue(AgentContextImportance.isValuable("见 https://example.com/docs/page"))
        assertTrue(AgentContextImportance.isValuable("commit 3aed119188c54dc2"))
        assertFalse(AgentContextImportance.isValuable("嗯好的"))
    }

    @Test
    fun `关键事实提取按约束优先去重`() {
        val messages = listOf(
            AgentModelClient.ConversationMessage("user", "必须记住：输出用中文\n普通寒暄内容"),
            AgentModelClient.ConversationMessage("assistant", "已读 /data/local/tmp/report.txt"),
            AgentModelClient.ConversationMessage("user", "必须记住：输出用中文"),
        )
        val facts = AgentContextImportance.extractKeyFacts(messages)
        assertTrue("约束应排前面", facts.first().contains("必须记住"))
        assertEquals("应去重", 1, facts.count { it.contains("必须记住") })
        assertTrue(facts.any { it.contains("report.txt") })
    }

    @Test
    fun `事实覆盖宽松匹配改写与原句`() {
        assertTrue(AgentContextImportance.coversFact("摘要原样包含 /data/app/src/main", "/data/app/src/main"))
        // 宽松：长句复述前 12 字即算覆盖
        assertTrue(
            AgentContextImportance.coversFact(
                "摘要提到：必须完成登录流程并保存结果到本地文件",
                "必须完成登录流程并保存结果到本地文件和数据库",
            ),
        )
        assertFalse(AgentContextImportance.coversFact("摘要完全没有相关内容", "必须保留用户偏好设置"))
    }
}

class AgentHistoryTrimmerDigestTest {

    private fun msg(role: String, content: String) = JSONObject().put("role", role).put("content", content)
    private fun bulk(n: Int) = JSONObject().put("role", "tool").put("content",
        (1..n).joinToString("\n") { "第 $it 行输出 /data/x/y $it" })

    @Test
    fun `裁剪备忘保留被丢前缀里的约束与路径`() {
        val dropped = listOf(
            msg("user", "任务开始：必须完成登录流程"),
            msg("assistant", "查看了 /data/app/login.kt 继续"),
            bulk(50),
        )
        val digest = AgentHistoryTrimmer.buildTrimDigest(dropped)
        assertNotNull("应生成备忘", digest)
        val text = digest!!.optString("content")
        assertTrue("约束必须保留", text.contains("必须完成登录流程"))
        assertTrue("路径必须保留", text.contains("/data/app/login.kt"))
        assertTrue(digest.optBoolean("_eta_trim_digest"))
    }

    @Test
    fun `无可提取要点时不生成备忘`() {
        assertNull(AgentHistoryTrimmer.buildTrimDigest(listOf(msg("user", "嗯"))))
    }

    @Test
    fun `裁剪后注入备忘且保留连续尾部`() {
        // 大量旧消息（含约束）+ 少量新消息；窗口迫使裁剪
        val history = ArrayList<JSONObject>()
        history += msg("user", "必须记住：最终输出为 JSON")
        repeat(40) { history += bulk(60) }
        history += msg("user", "最新请求")
        history += msg("assistant", "好的")
        val outcome = AgentHistoryTrimmer.trimJson(history, window = 40_000, systemEstimate = 0, calibration = 1.0)
        assertTrue(outcome.trimmed)
        assertTrue("裁剪结果首条应是备忘", outcome.messages.first().optBoolean("_eta_trim_digest"))
        assertTrue("备忘内容含约束", outcome.messages.first().optString("content").contains("最终输出为 JSON"))
        assertTrue("尾部应是最新消息", outcome.messages.last().optString("content") == "好的")
    }

    @Test
    fun `窗口足够时不动历史`() {
        val history = listOf(msg("user", "你好"), msg("assistant", "好的"))
        val outcome = AgentHistoryTrimmer.trimJson(history, window = 100_000, systemEstimate = 0, calibration = 1.0)
        assertFalse(outcome.trimmed)
        assertEquals(2, outcome.messages.size)
    }
}
