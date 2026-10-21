package io.github.mangi.eta.agent.memory

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AgentMemoryFreshness] 的时效评估测试。
 *
 * 时间一律由测试注入，不用 `LocalDate.now()` —— 否则这些用例会在某一天突然变红。
 */
class AgentMemoryFreshnessTest {

    private val today = LocalDate.of(2026, 10, 18)

    @Test
    fun `没有日期时不打扰`() {
        val r = AgentMemoryFreshness.assess("Debian 13 上跑 qemu，一切正常", today)
        assertNull(r.latestDate)
        assertNull(r.ageDays)
        assertFalse(r.stale)
        assertNull("没有日期就不给提示", r.hint())
    }

    @Test
    fun `刚写下的内容不算过时`() {
        val r = AgentMemoryFreshness.assess("2026-10-17 验证通过", today)
        assertEquals("2026-10-17", r.latestDate)
        assertEquals(1L, r.ageDays)
        assertFalse(r.stale)
        assertNull(r.hint())
    }

    @Test
    fun `超过三十天标记过时并给出提示`() {
        val r = AgentMemoryFreshness.assess("（2026-09-01 结项）", today)
        assertEquals("2026-09-01", r.latestDate)
        assertEquals(47L, r.ageDays)
        assertTrue(r.stale)
        val hint = r.hint()
        assertTrue("提示应包含日期与天数", hint != null && hint.contains("2026-09-01") && hint.contains("47"))
    }

    @Test
    fun `恰好三十天不算过时`() {
        // 边界：STALE_DAYS 是 30，age == 30 时不标记（用 > 而非 >=）
        val r = AgentMemoryFreshness.assess("2026-09-18 记录", today)
        assertEquals(30L, r.ageDays)
        assertFalse(r.stale)
    }

    @Test
    fun `多日期取最晚的一个`() {
        val r = AgentMemoryFreshness.assess(
            "2026-08-01 开始，2026-10-15 完成，2026-09-20 中间检查",
            today,
        )
        assertEquals("2026-10-15", r.latestDate)
        assertEquals(3L, r.ageDays)
        assertFalse(r.stale)
    }

    @Test
    fun `未来日期按零天处理不算过时`() {
        val r = AgentMemoryFreshness.assess("计划 2026-12-01 发布", today)
        assertEquals("2026-12-01", r.latestDate)
        assertEquals(0L, r.ageDays)
        assertFalse(r.stale)
    }

    @Test
    fun `非法日期被忽略`() {
        // 2026-13-45 不是合法日期，应被丢弃而不是抛异常
        val r = AgentMemoryFreshness.assess("坏日期 2026-13-45，好日期 2026-10-10", today)
        assertEquals("2026-10-10", r.latestDate)
        assertEquals(8L, r.ageDays)
    }

    @Test
    fun `格式不符的不算日期`() {
        val r = AgentMemoryFreshness.assess("2026-10（只有年月）和 20261018", today)
        assertNull(r.latestDate)
    }

    @Test
    fun `真实记忆片段`() {
        val snippet = "配套 LSP 模块规范（用户指令 2026-10-02 起长期执行，v2 修订）"
        val r = AgentMemoryFreshness.assess(snippet, today)
        assertEquals("2026-10-02", r.latestDate)
        assertEquals(16L, r.ageDays)
        assertFalse("两周前的规则还不算过时", r.stale)
    }
}
