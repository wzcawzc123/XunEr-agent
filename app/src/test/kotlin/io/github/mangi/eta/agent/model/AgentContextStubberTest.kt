package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentContextStubberTest {

    private fun tool(id: String, content: String) = JSONObject()
        .put("role", "tool")
        .put("tool_call_id", id)
        .put("content", content)

    private fun user(text: String) = JSONObject().put("role", "user").put("content", text)

    @Test
    fun foldsOnlyToolResultsBeforeSecondLastUserTurn() {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", "固定规则"))   // systemCount=1
            .put(user("第 1 轮"))
            .put(tool("a", "老结果 ".repeat(500)))
            .put(user("第 2 轮"))
            .put(tool("b", "新结果 ".repeat(500)))
            .put(user("第 3 轮"))
        val folded = AgentContextStubber.stub(messages, systemCount = 1)

        assertEquals(1, folded)
        assertTrue(messages.getJSONObject(2).getString("content").startsWith(AgentContextStubber.STUB_PREFIX))
        // 第二轮的 tool 结果被保护（在倒数第二个 user 之后）
        assertFalse(messages.getJSONObject(4).getString("content").startsWith(AgentContextStubber.STUB_PREFIX))
    }

    @Test
    fun keepsToolCallIdAndIsIdempotent() {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", "s"))
            .put(user("u1"))
            .put(tool("call-42", "内容 ".repeat(200)))
            .put(user("u2"))
            .put(user("u3"))
        assertEquals(1, AgentContextStubber.stub(messages, 1))
        assertEquals("call-42", messages.getJSONObject(2).getString("tool_call_id"))
        val afterFirst = messages.getJSONObject(2).getString("content")
        // 再次调用不重复折叠
        assertEquals(0, AgentContextStubber.stub(messages, 1))
        assertEquals(afterFirst, messages.getJSONObject(2).getString("content"))
    }

    @Test
    fun skipsWhenFewerThanTwoUserTurns() {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", "s"))
            .put(user("u1"))
            .put(tool("a", "结果 ".repeat(300)))
        assertEquals(0, AgentContextStubber.stub(messages, 1))
        assertFalse(messages.getJSONObject(2).getString("content").startsWith(AgentContextStubber.STUB_PREFIX))
    }

    @Test
    fun stubCarriesLengthAndPreview() {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", "s"))
            .put(user("u1"))
            .put(tool("a", "很长的结果内容 ".repeat(50)))
            .put(user("u2"))
            .put(user("u3"))
        AgentContextStubber.stub(messages, 1)
        val stub = messages.getJSONObject(2).getString("content")
        assertTrue(stub.contains("原 "))
        assertTrue(stub.contains("很长的结果内容"))
    }
}
