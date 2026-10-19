package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AgentPlanContextProjection] 的纯 JVM 测试。
 *
 * 这个投影是「run 开始注入清单」的最后一公里：只改出站请求副本，不写持久历史。
 * 若它把键写回原消息、或改变角色顺序，Anthropic 的 thinking 签名/角色交替会直接 400。
 */
class AgentPlanContextProjectionTest {

    @Test
    fun `快照被投影为最后一条用户消息的文本块`() {
        val messages = JSONArray().put(JSONObject().put("role", "user").put("content", "你好"))
        AgentPlanContextProjection.attach(
            messages.getJSONObject(0),
            "[当前任务清单] 1/2 完成\n1. [已完成] 甲",
        )

        val projected = AgentPlanContextProjection.project(messages)

        val message = projected.getJSONObject(0)
        assertEquals("user", message.getString("role"))
        val content = message.getJSONArray("content")
        assertEquals(2, content.length())
        assertEquals("你好", content.getJSONObject(0).getString("text"))
        assertTrue(content.toString().contains("任务清单"))
        assertTrue(content.toString().contains("[已完成] 甲"))
        // 原消息不应被投影改写（除 attach 写入的瞬态键外，content 仍是字符串）。
        assertTrue(messages.getJSONObject(0).get("content") is String)
    }

    @Test
    fun `没有快照时返回原引用`() {
        val messages = JSONArray().put(JSONObject().put("role", "user").put("content", "你好"))
        assertSame(messages, AgentPlanContextProjection.project(messages))
    }

    @Test
    fun `空白快照不写入`() {
        val message = JSONObject().put("role", "user").put("content", "你好")
        AgentPlanContextProjection.attach(message, "  ")
        assertFalse(message.has("_eta_plan_snapshot"))
    }
}
