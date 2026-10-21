package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ceil

/**
 * 本地估算的精度与校准行为。
 *
 * 真机取证（2026-10-08，会话「性能调度模块-新会话」）：未校准的估算 / 实测 = 4.82，
 * 导致硬裁剪在实测仅 28.5 万时就按"已超 100 万窗口"触发，旧消息被纯丢弃、摘要从未更新。
 * 本用例集锁住两点：中文折算系数、以及校准必须能**向下**修正。
 */
class AgentContextBudgetCalibrationTest {

    @Test
    fun `英文仍按 3 字符每 token 折算`() {
        val tokens = AgentContextBudget.textTokens("a".repeat(3_000))
        assertTrue("实际 $tokens，应接近 1000", tokens in 995..1_005)
    }

    @Test
    fun `估算高估时校准向下修正`() {
        val budget = AgentContextBudget(1_000_000)
        val messages = JSONArray()
        val raw = AgentContextBudget.rawEstimate(messages).coerceAtLeast(1)

        // 复现真机比例：实测只有估算的 1/4.82
        budget.observeTokens(raw, (raw * 4.82).toInt())
        val judged = budget.estimate(messages, JSONArray())

        assertTrue("校准后应回到实测量级（judged=$judged raw=$raw）", judged <= raw)
    }

    @Test
    fun `未观测时使用中性初值不假设估算方向`() {
        // P2-1 修正（2026-10-16，见 docs/reviews/context-compaction-audit.md）：
        // 原断言「未观测时 estimated < raw」建立在「本地估算必然高估」这一**单一真机样本**上
        // （4.82 倍，中文密集会话）。复核发现工具结果密集的会话恰好相反——实测
        // calibration ≈ 1.85（估算 420,083 vs 实测 777,273，低估）。固定按 0.4 缩小会造成
        // 4.6 倍的方向性偏差。
        // 现改为中性初值 1.0：未观测时不假设方向（estimated == raw），
        // 收到 usage 后立即被实测校准覆盖（见「双向校准」用例）。
        val budget = AgentContextBudget(1_000_000)
        val messages = JSONArray()
        val raw = AgentContextBudget.rawEstimate(messages)
        val estimated = budget.estimate(messages, JSONArray())
        assertEquals("未观测时应等于原始估算（中性、不假设方向）", raw, estimated)
    }

    @Test
    fun `非法实测值不影响校准`() {
        val budget = AgentContextBudget(1_000_000)
        val messages = JSONArray()
        val before = budget.estimate(messages, JSONArray())
        budget.observeTokens(null, 1_000)
        budget.observeTokens(0, 1_000)
        budget.observeTokens(-5, 1_000)
        budget.observeTokens(1_000, 0)
        assertEquals(before, budget.estimate(messages, JSONArray()))
    }

    @Test
    fun `校准系数被夹在下限与上限之间`() {
        val budget = AgentContextBudget(1_000_000)
        val messages = JSONArray().put(JSONObject().put("role", "user").put("content", "你好"))
        val raw = AgentContextBudget.rawEstimate(messages)
        budget.observeTokens(5, 100)          // 比例 0.05 → 夹到下限 0.1
        val atFloor = budget.estimate(messages, JSONArray())
        budget.observeTokens(1_000, 100)      // 比例 10.0 → 夹到上限 8.0
        val atCap = budget.estimate(messages, JSONArray())
        assertEquals(ceil(raw * 0.1).toInt(), atFloor)
        assertEquals(ceil(raw * 8.0).toInt(), atCap)
    }

    @Test
    fun `极小窗口或未知窗口的触发线与保留预算退化而非失败`() {
        // triggerTokens：窗口比例取整为 0 时判为无触发线（不自动压缩）
        assertNull(AgentContextBudget.triggerTokens(1))
        assertNull(AgentContextBudget.triggerTokens(0))
        assertNull(AgentContextBudget.triggerTokens(null))
        // retainTokens：极小窗口（≤6）按比例取整后退化到 0，不返回 null
        assertEquals(0, AgentContextBudget.retainTokens(1))
        assertNull(AgentContextBudget.retainTokens(null))
    }
}
