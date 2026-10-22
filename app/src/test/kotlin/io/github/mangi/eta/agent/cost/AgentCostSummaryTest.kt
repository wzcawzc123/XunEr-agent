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

    @Test
    fun `byModel 汇总与总账同口径且各自计算可计费输入`() {
        // U3（v3.13.4）：byModel 由 Repository 从 DAO 投影构造；这里锁定 ByModel 的
        // 可计费输入与 displayName 兜底（null modelId → 未绑定模型）。
        val byModel = listOf(
            AgentCostSummary.ByModel("model-a", turns = 4, inputTokens = 200, cachedTokens = 60, outputTokens = 30, reasoningTokens = 0),
            AgentCostSummary.ByModel(null, turns = 2, inputTokens = 100, cachedTokens = 0, outputTokens = 20, reasoningTokens = 0),
        )
        assertEquals(140L, byModel[0].billableInputTokens)
        assertEquals(100L, byModel[1].billableInputTokens)

        // 与 fromRows 同口径核对总账（Row = 一条消息记录，turns 按条数累加）。
        val total = AgentCostSummary.fromRows(
            byModel.map { AgentCostSummary.Row(it.inputTokens, it.cachedTokens, it.outputTokens, it.reasoningTokens) },
        )
        assertEquals(2, total.turns)
        assertEquals(300L, total.inputTokens)
        assertEquals(60L, total.cachedTokens)
    }

    @Test
    fun `byModel 展示名解析提供商与模型且未解析时带短 id 兜底`() {
        // 2026-10-22 真机反馈：用户自建模型的 modelId 是 UUID，直接显示等于没显示。
        val resolved = AgentCostSummary.ByModel(
            "3aed1191-88c5-4dc2-962f-171d91db426f", turns = 1,
            inputTokens = 10, cachedTokens = 0, outputTokens = 1, reasoningTokens = 0,
            modelName = "sensenova-6.8-flash-lite", providerName = "商汤",
        )
        val modelOnly = resolved.copy(providerName = null)
        val unresolved = resolved.copy(modelName = null, providerName = null)
        val unbound = resolved.copy(modelId = null)

        assertEquals("商汤 / sensenova-6.8-flash-lite", resolved.displayName)
        assertEquals("sensenova-6.8-flash-lite", modelOnly.displayName)
        assertEquals("未知模型（3aed1191）", unresolved.displayName)
        assertEquals("未绑定模型", unbound.displayName)
    }
}

/** v3.13.9：总账与「按模型」必须是同一份数据的两种视角，分组不许为空。 */
class AgentCostSummaryFromModelsTest {
    private fun model(id: String, input: Long, cached: Long, out: Long) =
        AgentCostSummary.ByModel(
            modelId = id,
            turns = 2,
            inputTokens = input,
            cachedTokens = cached,
            outputTokens = out,
            reasoningTokens = 0,
        )

    @Test
    fun `总账等于各模型之和`() {
        val summary = AgentCostSummary.fromModels(
            listOf(
                model("a", input = 1_000, cached = 900, out = 10),
                model("b", input = 500, cached = 0, out = 5),
            ),
        )
        assertEquals(4, summary.turns)
        assertEquals(1_500, summary.inputTokens)
        assertEquals(900, summary.cachedTokens)
        assertEquals(15, summary.outputTokens)
    }

    @Test
    fun `分组按输入 token 降序且不为空`() {
        // 这条断言直接守住「按模型卡片永远不显示」那个回归：
        // 只要 fromModels 没把 byModel 填上，渲染条件 isNotEmpty() 就恒假。
        val summary = AgentCostSummary.fromModels(
            listOf(
                model("small", input = 100, cached = 0, out = 1),
                model("large", input = 9_000, cached = 8_000, out = 20),
            ),
        )
        assertEquals(listOf("large", "small"), summary.byModel.map { it.modelId })
        assertEquals(1_000L, summary.byModel.first().billableInputTokens)
    }

    @Test
    fun `空分组得到空总结而不报错`() {
        val summary = AgentCostSummary.fromModels(emptyList())
        assertTrue(summary.isEmpty)
        assertTrue(summary.byModel.isEmpty())
    }
}
