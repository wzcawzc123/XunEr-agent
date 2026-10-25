package io.github.mangi.eta.agent.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentContextBudgetPredictionTest {

    @Test
    fun `预测占用取增长与输出预留的较大者`() {
        // 窗口 100k：输出预留 = min(16384, 100000/16=6250) = 6250
        assertEquals(60_000 + 6_250, AgentContextBudget.predictedNextTokens(60_000, 0, 100_000))
        assertEquals(60_000 + 50_000, AgentContextBudget.predictedNextTokens(60_000, 50_000, 100_000))
        assertNull(AgentContextBudget.predictedNextTokens(60_000, 0, null))
        assertNull(AgentContextBudget.predictedNextTokens(60_000, 0, 0))
    }

    @Test
    fun `预测触发条件窗口内不触发单轮暴涨提前触发`() {
        val window = 100_000
        val trigger = requireNotNull(AgentContextBudget.triggerTokens(window))
        // 平稳：before 低于触发线，预测也低于窗口 → 不该触发
        val calmBefore = trigger - 10_000
        val calmPredicted = AgentContextBudget.predictedNextTokens(calmBefore, 1_000, window)!!
        assertFalse(calmPredicted >= window)
        // 暴涨：before 仍低于触发线，但预测已经越过窗口 → 应提前触发
        val spikeBefore = trigger - 10_000
        val spikePredicted = AgentContextBudget.predictedNextTokens(spikeBefore, 60_000, window)!!
        assertTrue(spikeBefore < trigger && spikePredicted >= window)
    }

    @Test
    fun `缓存尾部用户轮常量为 2`() {
        assertEquals(2, AgentContextCompactor.CACHE_TAIL_USER_TURNS)
    }
}
