package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentSensitiveTranscriptTest {
    @Test
    fun memoryToolArgumentsAndResultsAreAlwaysSensitive() {
        assertTrue(AgentSensitiveToolPolicy.isSensitive("memory_get"))
        assertTrue(AgentSensitiveToolPolicy.isSensitive("memory_write"))
        assertTrue(AgentSensitiveToolPolicy.isSensitive("search_coloros_memories"))
        assertTrue(AgentSensitiveToolPolicy.isSensitive("search_notification_history"))
        assertTrue(AgentSensitiveToolPolicy.isSensitive("recent_app_activity"))
        assertTrue(AgentSensitiveToolPolicy.isSensitive("get_health_summary"))
        assertTrue(AgentSensitiveToolPolicy.isSensitive("search_personal_orders"))
        assertTrue(AgentSensitiveToolPolicy.isSensitive("mcp_server_search_deadbeef"))
    }

    @Test
    fun readImageIsNotSensitiveSoItsArgumentsSurviveRounds() {
        // 功能优先：read_image 参数被脱敏会让下一轮模型拿到占位符无法归因（串图/回环根因）。
        assertFalse(AgentSensitiveToolPolicy.isSensitive("read_image"))
    }

    @Test
    fun sensitiveToolArgumentsAndResultAreRemovedTogether() {
        val callId = "call_sensitive"
        val messages = JSONArray()
            .put(
                JSONObject()
                    .put("role", "assistant")
                    .put("content", JSONObject.NULL)
                    .put(
                        "tool_calls",
                        JSONArray().put(
                            JSONObject()
                                .put("id", callId)
                                .put("type", "function")
                                .put(
                                    "function",
                                    JSONObject()
                                        .put("name", "set_setting")
                                        .put(
                                            "arguments",
                                            """{"namespace":"global","key":"demo","value":"敏感值"}""",
                                        ),
                                ),
                        ),
                    ),
            )
            .put(
                JSONObject()
                    .put("role", "tool")
                    .put("tool_call_id", callId)
                    .put("content", """{"ok":true,"password":"secret-value"}"""),
            )

        val encoded = AgentConversationCodec.transcript(
            messages = messages,
            startIndex = 0,
            sensitiveToolCallIds = setOf(callId),
        ).joinToString { it.content + it.toolCallsJson }

        assertFalse(encoded.contains("敏感值"))
        assertFalse(encoded.contains("secret-value"))
        assertTrue(encoded.contains("redacted"))
        assertTrue(encoded.contains("未写入持久会话"))

        // 脱敏仍须保留"形状"：模型要能看出自己调过哪些字段、拿到什么结构，
        // 否则连"这条已经做过了"都判断不了。取值一律不留。
        assertTrue(encoded.contains("_redacted"))
        assertTrue(encoded.contains("_fields"))
        assertTrue(encoded.contains("namespace"))
        assertTrue(encoded.contains("str:"))
        // 但取值一律不留，连长度之外的信息都没有。
        assertFalse(encoded.contains("demo"))
        assertFalse(encoded.contains("global"))
    }
}
