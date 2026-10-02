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

        // 脱敏仍须保留"形状"：模型必须能看出自己调过哪些字段、拿到什么结构，
        // 否则看不到调用记录就只能反复重试（真机实测：同一会话 34 次重复 memory_get）。
        assertTrue(encoded.contains("_redacted"))
        assertTrue(encoded.contains("_fields"))
        assertTrue(encoded.contains("namespace"))
        assertTrue(encoded.contains("str:"))
        // 但取值一律不留，连长度之外的信息都没有。
        assertFalse(encoded.contains("demo"))
        assertFalse(encoded.contains("global"))
    }

    @Test
    fun memoryResultsStayReadableWhileArgumentsAndOtherSensitiveResultsStayRedacted() {
        val memoryCall = "call_memory"
        val settingsCall = "call_settings"
        val messages = JSONArray()
            .put(
                JSONObject()
                    .put("role", "assistant")
                    .put("content", JSONObject.NULL)
                    .put(
                        "tool_calls",
                        JSONArray()
                            .put(
                                JSONObject()
                                    .put("id", memoryCall)
                                    .put("type", "function")
                                    .put(
                                        "function",
                                        JSONObject()
                                            .put("name", "memory_get")
                                            .put("arguments", """{"section":"隐藏的章节名"}"""),
                                    ),
                            )
                            .put(
                                JSONObject()
                                    .put("id", settingsCall)
                                    .put("type", "function")
                                    .put(
                                        "function",
                                        JSONObject()
                                            .put("name", "set_setting")
                                            .put("arguments", """{"value":"敏感值"}"""),
                                    ),
                            ),
                    ),
            )
            .put(
                JSONObject()
                    .put("role", "tool")
                    .put("tool_call_id", memoryCall)
                    .put("content", """{"ok":true,"content":"1: # 某节\n2: Eta Agent"}"""),
            )
            .put(
                JSONObject()
                    .put("role", "tool")
                    .put("tool_call_id", settingsCall)
                    .put("content", """{"ok":true,"password":"secret-value"}"""),
            )

        val encoded = AgentConversationCodec.transcript(
            messages = messages,
            startIndex = 0,
            sensitiveToolCallIds = setOf(memoryCall, settingsCall),
        ).joinToString { it.content + it.toolCallsJson }

        // 记忆正文保留进历史：否则模型在后续轮次看不到自己刚读到的内容，只能反复重读。
        assertTrue(encoded.contains("Eta Agent"))
        // memory_get 的参数仍然按形状脱敏，不泄露取值。
        assertFalse(encoded.contains("隐藏的章节名"))
        assertTrue(encoded.contains("_redacted"))
        // 其它敏感工具的结果照旧被抹掉。
        assertFalse(encoded.contains("secret-value"))
        assertFalse(encoded.contains("敏感值"))
        assertTrue(encoded.contains("未写入持久会话"))
    }
}
