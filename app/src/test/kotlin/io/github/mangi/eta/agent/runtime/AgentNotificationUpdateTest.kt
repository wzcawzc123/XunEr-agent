package io.github.mangi.eta.agent.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 通知片段与节流（2026-10-28 通知显示执行输出）。
 *
 * 平台限速 5 条/秒且超限**静默丢弃**（AOSP），所以节流间隔必须留余量，
 * 且"最后一次"必须由补发保证（urgent 路径），测试锁定这两个口径。
 */
class AgentNotificationUpdateTest {

    @Test
    fun delaySince_gatesWithinIntervalAndAllowsAfter() {
        val interval = AgentNotificationUpdate.MIN_CONTENT_INTERVAL_MS
        assertEquals("窗口内需等待剩余时间", interval - 200, AgentNotificationUpdate.delaySince(0, 200, interval))
        assertEquals("窗口外可立即发送", 0L, AgentNotificationUpdate.delaySince(0, interval, interval))
        // 时钟异常（lastAt 在未来）时最多等一个周期，不会无限推后
        assertEquals("时钟回拨最多等一个周期", interval, AgentNotificationUpdate.delaySince(9999, 0, interval))
    }

    @Test
    fun delaySince_defaultIntervalLeavesHeadroomUnderPlatformRateLimit() {
        // 平台默认限速 5/s；内容更新 1s/次为 1/s，留 80% 余量给同包其他通知
        assertTrue(AgentNotificationUpdate.MIN_CONTENT_INTERVAL_MS >= 1_000L)
        // 每秒最多发 1000/interval 条；必须 ≤ 平台默认限速 5/s
        assertTrue(1_000L / AgentNotificationUpdate.MIN_CONTENT_INTERVAL_MS <= 5L)
    }

    @Test
    fun tailSnippet_keepsTailAndMarksTruncation() {
        val long = "前面说的应该被丢掉 ".repeat(20) + "结尾最重要的话"
        val snippet = AgentNotificationUpdate.tailSnippet(long, maxChars = 20)
        assertTrue("超长前置省略号", snippet.startsWith("…"))
        assertTrue("保留尾部", snippet.contains("结尾最重要的话"))
        assertTrue("整体不超长", snippet.length <= 21)
    }

    @Test
    fun tailSnippet_flattensWhitespaceAndKeepsShortText() {
        assertEquals("a b c", AgentNotificationUpdate.tailSnippet("  a   b\n\tc  "))
        assertEquals("", AgentNotificationUpdate.tailSnippet("   \n  "))
    }
}
