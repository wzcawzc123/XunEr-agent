package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 请求前缀稳定性（缓存友好性）测试。
 *
 * 动机（2026-10-26 缓存命中率审查）：provider 按前缀缓存计费，任何对**老消息**的
 * 非确定性改写都会从改动点起全部 miss。这里锁定三个高频变换的幂等/确定性质：
 * 同一输入两次变换结果必须字节一致，防止未来改动引入「隐形抖动」。
 */
class AgentRequestStabilityTest {

    private fun textMessage(role: String, text: String, extras: JSONObject.() -> Unit = {}) =
        JSONObject().put("role", role).put("content", text).apply(extras)

    private fun sampleMessages(): JSONArray = JSONArray()
        .put(textMessage("user", "打开设置"))
        .put(textMessage("assistant", "已打开") {
            put("_eta_assistant_screen_context", "com.android.settings/.Main")
        })
        .put(textMessage("user", "继续"))

    @Test
    fun `屏幕上下文投影两次调用输出字节一致`() {
        val first = AssistantScreenContextProjection.project(sampleMessages()).toString()
        val second = AssistantScreenContextProjection.project(sampleMessages()).toString()
        assertEquals("同输入两次投影必须一致", first, second)
    }

    @Test
    fun `屏幕上下文投影幂等再投影不再变化`() {
        val once = AssistantScreenContextProjection.project(sampleMessages())
        val twice = AssistantScreenContextProjection.project(JSONArray(once.toString()))
        assertEquals("投影后再投影应稳定", once.toString(), twice.toString())
    }

    @Test
    fun `投影不修改输入消息数组`() {
        val original = sampleMessages()
        val snapshot = original.toString()
        AssistantScreenContextProjection.project(original)
        assertEquals("project 不得改动输入", snapshot, original.toString())
    }

    @Test
    fun `工具结果修剪幂等`() {
        val big = (1..200).joinToString("\n") { "第 $it 行 /data/app/build $it KB" }
        val once = AgentToolResultPruner.prune(big)
        val twice = AgentToolResultPruner.prune(once)
        assertEquals("修剪两次结果必须一致", once, twice)
        assertTrue("应显著变小", once.length < big.length)
    }

    @Test
    fun `小工具结果原样返回不引入变化`() {
        val small = "ok"
        assertSame("未超预算应返回原引用", small, AgentToolResultPruner.prune(small))
    }

    @Test
    fun `工具结果瘦身幂等`() {
        val big = (1..90).joinToString("\n") { "输出行 $it：/data/app/xxx/build.gradle 第 $it 段占位" }
        val messages = listOf(
            AgentModelClient.ConversationMessage("user", "任务一"),
            AgentModelClient.ConversationMessage("tool", big, toolCallId = "call-1"),
            AgentModelClient.ConversationMessage("user", "任务二"),
            AgentModelClient.ConversationMessage("tool", "ok", toolCallId = "call-2"),
        )
        val once = AgentToolResultSlimmer.slim(messages, keepRecentTurns = 1)
        val twice = AgentToolResultSlimmer.slim(once, keepRecentTurns = 1)
        assertEquals("瘦身两次结果必须一致", once, twice)
    }
}
