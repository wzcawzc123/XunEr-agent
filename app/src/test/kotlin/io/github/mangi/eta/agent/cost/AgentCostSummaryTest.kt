package io.github.mangi.eta.agent.cost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AgentCostSummary] 的纯逻辑测试。
 *
 * 重点覆盖两类容易出错的地方：
 * - **可计费输入**（input − cached）在异常数据下不能变负；
 * - **命中率**在零输入、缓存大于输入时不能出现 NaN / >100%。
 */
class AgentCostSummaryTest {

    private fun row(input: Long, cached: Long? = null, output: Long = 0, reasoning: Long? = null) =
        AgentCostSummary.Row(input, cached, output, reasoning)

    @Test
    fun `空列表汇总为零且标记为空态`() {
        val s = AgentCostSummary.fromRows(emptyList())
        assertEquals(AgentCostSummary.EMPTY, s)
        assertTrue(s.isEmpty)
        assertEquals(0f, s.cacheHitRate, 0.0001f)
    }

    @Test
    fun `正常累加并计算命中率`() {
        val s = AgentCostSummary.fromRows(
            listOf(
                row(input = 1_000, cached = 900, output = 50),
                row(input = 3_000, cached = 2_700, output = 150),
            ),
        )
        assertEquals(2, s.turns)
        assertEquals(4_000L, s.inputTokens)
        assertEquals(3_600L, s.cachedTokens)
        assertEquals(200L, s.outputTokens)
        assertEquals(400L, s.billableInputTokens)
        assertEquals(0.9f, s.cacheHitRate, 0.0001f)
    }

    @Test
    fun `input 为 0 的记录不计入轮次`() {
        val s = AgentCostSummary.fromRows(
            listOf(
                row(input = 0, cached = 0, output = 10),
                row(input = 500, cached = 0, output = 20),
            ),
        )
        assertEquals("没有 input 的本地占位不该算一轮", 1, s.turns)
        assertEquals(500L, s.inputTokens)
    }

    @Test
    fun `cached 为 null 时按零计且不虚高命中率`() {
        val s = AgentCostSummary.fromRows(listOf(row(input = 1_000, cached = null)))
        assertEquals(0L, s.cachedTokens)
        assertEquals(0f, s.cacheHitRate, 0.0001f)
        assertEquals(1_000L, s.billableInputTokens)
    }

    @Test
    fun `cached 大于 input 时不出负数也不超一百百分比`() {
        // 某些兼容端点会在流式回执里重复累加 cached，导致脏数据。
        val s = AgentCostSummary.fromRows(listOf(row(input = 100, cached = 250)))
        assertEquals("可计费输入不能为负", 0L, s.billableInputTokens)
        assertTrue("命中率不能超过 1", s.cacheHitRate <= 1f)
        assertEquals(1f, s.cacheHitRate, 0.0001f)
    }

    @Test
    fun `负值被忽略而不是把总数拉低`() {
        val s = AgentCostSummary.fromRows(
            listOf(
                row(input = 1_000, cached = -50, output = -10, reasoning = -5),
            ),
        )
        assertEquals(1_000L, s.inputTokens)
        assertEquals(0L, s.cachedTokens)
        assertEquals(0L, s.outputTokens)
        assertEquals(0L, s.reasoningTokens)
    }

    @Test
    fun `十亿级输入不溢出`() {
        val s = AgentCostSummary.fromRows(
            listOf(
                row(input = 1_500_000_000, cached = 1_400_000_000),
                row(input = 200_000_000, cached = 190_000_000),
            ),
        )
        assertEquals(1_700_000_000L, s.inputTokens)
        assertEquals(1_590_000_000L, s.cachedTokens)
        assertEquals(110_000_000L, s.billableInputTokens)
        assertEquals(0.935f, s.cacheHitRate, 0.001f)
    }
}
