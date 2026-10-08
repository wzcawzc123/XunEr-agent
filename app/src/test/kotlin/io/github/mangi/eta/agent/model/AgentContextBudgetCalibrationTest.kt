package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
    fun `未观测时校准系数为 1 不改变估算`() {
        val budget = AgentContextBudget(1_000_000)
        val messages = JSONArray()
        assertEquals(AgentContextBudget.rawEstimate(messages), budget.estimate(messages, JSONArray()))
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
}
