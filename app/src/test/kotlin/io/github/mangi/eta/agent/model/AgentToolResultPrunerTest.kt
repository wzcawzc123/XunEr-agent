package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具结果修剪器的行为锁定。
 *
 * 用例集参照 deepseek-harness `compaction-tool-result-pruner` 的测试边界（预算内不动、头尾保留、
 * 标记插入、幂等），并补充本机适配项：JSON 结构必须保持可解析、代理对不得被切裂。
 */
class AgentToolResultPrunerTest {

    private val threshold = AgentToolResultPruner.THRESHOLD_CHARS
    private val head = AgentToolResultPruner.HEAD_CHARS
    private val tail = AgentToolResultPruner.TAIL_CHARS
    private val marker = AgentToolResultPruner.MARKER

    @Test
    fun `budget内的文本原样返回且不产生新对象`() {
        val text = "ok: 终端命令执行完成"
        assertFalse(AgentToolResultPruner.isOverBudget(text))
        assertSame(text, AgentToolResultPruner.prune(text))
    }

    @Test
    fun `恰好等于阈值不修剪`() {
        val text = "x".repeat(threshold)
        assertFalse(AgentToolResultPruner.isOverBudget(text))
        assertSame(text, AgentToolResultPruner.prune(text))
    }

    @Test
    fun `超预算纯文本保留头尾并插入标记`() {
        val text = "H".repeat(6_000) + "M".repeat(6_000) + "T".repeat(3_000)
        assertTrue(AgentToolResultPruner.isOverBudget(text))

        val pruned = AgentToolResultPruner.prune(text)

        assertTrue("修剪后必须更短", pruned.length < text.length)
        assertTrue("结果必须落在阈值内", pruned.length <= threshold)
        assertTrue("头部保留", pruned.startsWith("H".repeat(head)))
        assertTrue("尾部保留", pruned.endsWith("T".repeat(tail)))
        assertTrue("中段标记", pruned.contains(marker))
        assertFalse("中段已移除", pruned.contains("M".repeat(100)))
    }

    @Test
    fun `超预算 JSON 仍可解析且大字段被修剪`() {
        val bigOutput = "build line\n".repeat(4_000)
        val payload = JSONObject()
            .put("ok", true)
            .put("code", "OK")
            .put("tool", "terminal")
            .put("output", bigOutput)
            .toString()
        assertTrue(AgentToolResultPruner.isOverBudget(payload))

        val pruned = AgentToolResultPruner.prune(payload)

        val parsed = JSONObject(pruned)
        assertEquals(true, parsed.getBoolean("ok"))
        assertEquals("OK", parsed.getString("code"))
        assertEquals("terminal", parsed.getString("tool"))
        val output = parsed.getString("output")
        assertTrue("大字段被修剪", output.length < bigOutput.length)
        assertTrue("大字段带标记", output.contains(marker))
        assertTrue("修剪后应在预算内", pruned.length <= threshold)
    }

    @Test
    fun `小字段与标量类型保持不变`() {
        val payload = JSONObject()
            .put("ok", true)
            .put("count", 42)
            .put("ratio", 0.5)
            .put("note", "短文本")
            .put("items", JSONArray().put(1).put(2))
            .put("output", "x".repeat(threshold + 1_000))
            .toString()

        val parsed = JSONObject(AgentToolResultPruner.prune(payload))

        assertEquals(true, parsed.getBoolean("ok"))
        assertEquals(42, parsed.getInt("count"))
        assertEquals(0.5, parsed.getDouble("ratio"), 0.0)
        assertEquals("短文本", parsed.getString("note"))
        assertEquals(2, parsed.getJSONArray("items").length())
    }

    @Test
    fun `嵌套结构保持形状`() {
        val deep = JSONObject()
            .put("data", JSONObject()
                .put("logs", JSONArray().put(JSONObject().put("text", "y".repeat(threshold + 500))))
                .put("name", "kept"))
            .toString()

        val parsed = JSONObject(AgentToolResultPruner.prune(deep))

        assertEquals("kept", parsed.getJSONObject("data").getString("name"))
        val logs = parsed.getJSONObject("data").getJSONArray("logs")
        assertEquals(1, logs.length())
        assertTrue(logs.getJSONObject(0).getString("text").contains(marker))
    }

    @Test
    fun `修剪幂等`() {
        val text = "A".repeat(30_000)
        val once = AgentToolResultPruner.prune(text)
        val twice = AgentToolResultPruner.prune(once)
        assertEquals("已修剪内容必须落在预算内，重复执行不再变化", once, twice)
    }

    @Test
    fun `JSON 修剪幂等`() {
        val payload = JSONObject().put("output", "z".repeat(20_000)).toString()
        val once = AgentToolResultPruner.prune(payload)
        val twice = AgentToolResultPruner.prune(once)
        assertEquals(once, twice)
    }

    @Test
    fun `不切裂代理对`() {
        val emoji = "\uD83D\uDE00"
        val text = emoji.repeat(5_000)
        val pruned = AgentToolResultPruner.prune(text)

        for (index in pruned.indices) {
            val ch = pruned[index]
            if (Character.isHighSurrogate(ch)) {
                assertTrue("高代理后必须跟低代理 @$index", index + 1 < pruned.length)
                assertTrue("高代理后必须跟低代理 @$index", Character.isLowSurrogate(pruned[index + 1]))
            }
            if (Character.isLowSurrogate(ch)) {
                assertTrue("低代理前必须是高代理 @$index", index > 0)
                assertTrue("低代理前必须是高代理 @$index", Character.isHighSurrogate(pruned[index - 1]))
            }
        }
    }

    @Test
    fun `空内容与非 JSON 文本不抛异常`() {
        assertEquals("", AgentToolResultPruner.prune(""))
        val weird = "{not json" + "k".repeat(20_000)
        val pruned = AgentToolResultPruner.prune(weird)
        assertTrue(pruned.length < weird.length)
        assertTrue(pruned.contains(marker))
    }

    @Test
    fun `JSON 顶层为数组时同样可解析`() {
        val payload = JSONArray()
            .put(JSONObject().put("text", "q".repeat(threshold + 2_000)))
            .toString()
        val pruned = AgentToolResultPruner.prune(payload)
        val parsed = JSONArray(pruned)
        assertEquals(1, parsed.length())
        assertTrue(parsed.getJSONObject(0).getString("text").contains(marker))
    }
}
