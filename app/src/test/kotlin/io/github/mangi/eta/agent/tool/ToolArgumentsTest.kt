package io.github.mangi.eta.agent.tool

import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * 写操作参数的「缺省 vs 空串」区分。
 *
 * 背景：replace_text 少传 new_text、replace_section 少传 content 时，旧实现按空串处理，
 * 结果是把内容静默删掉还回 ok:true。这里锁住「缺失必须报错、空串照常合法」。
 */
class ToolArgumentsTest {

    @Test
    fun explicitTextRejectsMissingField() {
        val args = JSONObject().put("old_text", "旧内容")
        val failure = assertThrows(JSONException::class.java) { args.explicitText("new_text") }
        assertEquals(
            "new_text 必须显式给出；若要删除该处内容，请显式传空字符串 new_text=\"\"",
            failure.message,
        )
    }

    @Test
    fun explicitTextAcceptsEmptyString() {
        val args = JSONObject().put("new_text", "")
        assertEquals("", args.explicitText("new_text"))
    }

    @Test
    fun explicitTextRejectsJsonNull() {
        val args = JSONObject().put("content", JSONObject.NULL)
        assertThrows(JSONException::class.java) { args.explicitText("content") }
    }

    @Test
    fun explicitTextReturnsRealText() {
        val args = JSONObject().put("content", "## 新的一节\n正文")
        assertEquals("## 新的一节\n正文", args.explicitText("content"))
    }
}
