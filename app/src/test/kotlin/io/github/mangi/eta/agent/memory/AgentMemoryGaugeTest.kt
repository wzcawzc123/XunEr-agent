package io.github.mangi.eta.agent.memory

import io.github.mangi.eta.data.repository.AgentMemorySnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 核心区水位口径回归（2026-10-28 真机 bug）。
 *
 * 事故：memory_get 曾传 null（=128k 默认窗口）计算预算，而注入侧用会话真实窗口（≥512k → 触顶
 * 32000）。同一份内容，注入侧报 23%、工具侧报 93%，把用户/模型带偏到"快去瘦身"。
 *
 * 锁定：提供窗口时预算随窗口变化；used/truncated 与注入侧同一套截断逻辑。
 */
class AgentMemoryGaugeTest {

    private fun snapshot(content: String) = AgentMemorySnapshot(
        content = content,
        revision = "rev-test",
        byteSize = content.toByteArray().size,
        lineCount = content.lines().size,
    )

    /** 核心区约 7507 字符：标题 6 + 换行 1 + 2500×3。 */
    private fun bigCore(): String = "# 核心记忆\n" + "规则行".repeat(2_500)

    @Test
    fun gaugeUsesRealWindowBudgetInsteadOfDefault() {
        val payload = AgentMemoryGauge.payload(snapshot(bigCore()), 1_000_000)

        // 1M 窗口 → 预算触顶 32000；7507/32000 ≈ 23%，余量充足
        assertEquals(32_000, payload.getInt("budget_chars"))
        assertEquals(23, payload.getInt("percent"))
        assertFalse("真实预算下不该报截断", payload.getBoolean("truncated"))
    }

    @Test
    fun gaugeFallsBackToDefaultWindowWhenWindowUnknown() {
        val payload = AgentMemoryGauge.payload(snapshot(bigCore()), null)

        // null 与注入侧同样落 128k 默认 → 8000 预算；此口径只在拿不到窗口时使用
        assertEquals(8_000, payload.getInt("budget_chars"))
        assertEquals(93, payload.getInt("percent"))
        assertFalse(payload.getBoolean("truncated"))
    }

    @Test
    fun gaugeReportsTruncationWithSmallWindow() {
        val payload = AgentMemoryGauge.payload(snapshot(bigCore()), 64_000)

        // 64k 窗口 → 预算 4000（下限）；内容超预算 → 按行边界截断，只剩标题行
        assertEquals(4_000, payload.getInt("budget_chars"))
        assertTrue("超预算必须报 truncated", payload.getBoolean("truncated"))
        assertEquals("# 核心记忆".length, payload.getInt("used_chars"))
        assertEquals(1, payload.getInt("injected_lines"))
        assertEquals(2, payload.getInt("total_lines"))
    }

    @Test
    fun gaugeBudgetMatchesInjectionSideForSameWindow() {
        // 与注入侧同一套预算函数：同窗口下 core_usage.budget_chars == 注入用预算
        for (window in listOf(16_000, 128_000, 256_000, 1_000_000)) {
            val payload = AgentMemoryGauge.payload(snapshot(bigCore()), window)
            assertEquals(
                "window=$window",
                AgentMemoryContextBuilder.coreBudgetChars(window),
                payload.getInt("budget_chars"),
            )
        }
    }
}
