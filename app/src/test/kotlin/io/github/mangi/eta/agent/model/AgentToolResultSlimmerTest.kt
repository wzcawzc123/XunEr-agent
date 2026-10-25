package io.github.mangi.eta.agent.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolResultSlimmerTest {

    private fun user(text: String = "任务") = AgentModelClient.ConversationMessage("user", text)
    private fun tool(content: String, toolCallId: String = "call-1") =
        AgentModelClient.ConversationMessage("tool", content, toolCallId = toolCallId)

    private fun assistantWithCalls(vararg ids: String) = AgentModelClient.ConversationMessage(
        "assistant", "调用工具",
        toolCallsJson = ids.joinToString(
            prefix = "[", postfix = "]",
        ) { """{"id":"$it","function":{"name":"terminal"}}""" },
    )

    private fun bigResult(lines: Int = 90): String =
        (1..lines).joinToString("\n") { "输出行 $it：/data/app/xxx/build.gradle 第 $it 段很长的输出内容占位" }

    @Test
    fun `近期轮工具结果原样保留更旧的被瘦身`() {
        // 轮 1（旧）：user + assistant + 大工具结果 → 应瘦身
        // 轮 2、3（近）：user + 大工具结果 → 应保留
        val messages = listOf(
            user("任务一"), assistantWithCalls("call-1"), tool(bigResult(), "call-1"),
            user("任务二"), tool(bigResult(), "call-2"),
            user("任务三"), tool(bigResult(), "call-3"),
        )
        val slimmed = AgentToolResultSlimmer.slim(messages, keepRecentTurns = 2)
        assertTrue("旧轮结果应被瘦身", slimmed[2].content.contains(AgentToolResultSlimmer.MARKER))
        assertTrue("近期结果应保留原文", slimmed[4].content.startsWith("输出行 1"))
        assertTrue("最近结果应保留原文", slimmed[6].content.startsWith("输出行 1"))
    }

    @Test
    fun `小结果不瘦身且整体无改动时返回原引用`() {
        val messages = listOf(user("任务"), tool("ok"))
        val slimmed = AgentToolResultSlimmer.slim(messages, keepRecentTurns = 2)
        assertSame("无改动应返回原引用", messages, slimmed)
    }

    @Test
    fun `用户消息不足保留轮数时不瘦身`() {
        val messages = listOf(tool(bigResult(), "call-1"))
        val slimmed = AgentToolResultSlimmer.slim(messages, keepRecentTurns = 2)
        assertSame("没有用户轮应全部保留", messages, slimmed)
    }

    @Test
    fun `瘦身保留工具名成败与关键行且压缩体积`() {
        val output = (0 until 30).joinToString("\n") { "第 $it 行 /workspace/readme.md 普通内容" }
        val raw = org.json.JSONObject()
            .put("ok", false).put("code", "EXIT_1").put("output", output)
            .toString()
        val slim = AgentToolResultSlimmer.slimContent(raw, "terminal")
        assertTrue(slim.contains("tool=terminal"))
        assertTrue(slim.contains("失败"))
        assertTrue(slim.contains("code=EXIT_1"))
        assertTrue("应保留路径关键行", slim.contains("readme.md"))
        assertTrue("瘦身后应显著变小", slim.length < raw.length / 2)
    }

    @Test
    fun `瘦身不破坏 toolCallId 链接`() {
        val messages = listOf(
            user("任务一"), assistantWithCalls("call-9"), tool(bigResult(), "call-9"),
            user("任务二"), tool("ok", "call-8"),
        )
        val slimmed = AgentToolResultSlimmer.slim(messages, keepRecentTurns = 1)
        assertEquals("call-9", slimmed[2].toolCallId)
        assertEquals("call-8", slimmed[4].toolCallId)
    }

    @Test
    fun `工具名从 assistant 的 toolCallsJson 反查`() {
        val messages = listOf(
            user("任务一"), assistantWithCalls("call-7"), tool(bigResult(), "call-7"),
            user("任务二"), tool("ok"),
        )
        val slimmed = AgentToolResultSlimmer.slim(messages, keepRecentTurns = 1)
        assertTrue(slimmed[2].content.contains("tool=terminal"))
    }

    @Test
    fun `空结果与异常 JSON 不炸`() {
        val messages = listOf(
            user("任务一"), tool("", "call-1"), tool("{not-json", "call-2"),
            user("任务二"), tool("ok"),
        )
        val slimmed = AgentToolResultSlimmer.slim(messages, keepRecentTurns = 1)
        // 空/短结果未过阈值不瘦身；非法 JSON 走文本路径也不崩
        assertFalse(slimmed[2].content.contains(AgentToolResultSlimmer.MARKER))
    }
}
