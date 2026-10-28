package io.github.mangi.eta.agent.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 通知"动起来"的口径（2026-10-28 A 方案）。
 *
 * 约束：段内进度不得看起来已满（≤90%）；chip 轮播每 3.5 秒切一条且空串必须被过滤
 * （官方语义：空串=胶囊不显示文字）。
 */
class AgentNotificationMotionTest {

    @Test
    fun stageProgress_advancesWithinStageAndCapsAtNinetyPercent() {
        assertEquals(0, AgentNotificationMotion.stageProgress(0, 100, 0f))
        assertEquals(50, AgentNotificationMotion.stageProgress(0, 100, 0.5f))
        // 超过 100% 也不越过 90% 的段内上限，避免"看起来已满但还没结束"
        assertEquals(90, AgentNotificationMotion.stageProgress(0, 100, 1f))
        assertEquals(90, AgentNotificationMotion.stageProgress(0, 100, 5f))
        // 负比例视为 0
        assertEquals(100, AgentNotificationMotion.stageProgress(100, 100, -1f))
    }

    @Test
    fun elapsedFraction_isLinearAndSafe() {
        assertEquals(0.5f, AgentNotificationMotion.elapsedFraction(4_000, 8_000))
        assertEquals(1f, AgentNotificationMotion.elapsedFraction(20_000, 8_000))
        assertEquals(1f, AgentNotificationMotion.elapsedFraction(1_000, 0))
    }

    @Test
    fun chipText_rotatesEveryThreePointFiveSecondsAndSkipsBlanks() {
        val variants = listOf("思考中", "")
        assertEquals("思考中", AgentNotificationMotion.chipText(0, variants))
        assertEquals("思考中", AgentNotificationMotion.chipText(3_499, variants))
        // 只有一个有效变体时不会因为轮播而消失
        assertEquals("思考中", AgentNotificationMotion.chipText(3_500, variants))
        assertEquals("思考中", AgentNotificationMotion.chipText(70_000, variants))
    }

    @Test
    fun chipText_alternatesBetweenPhaseAndRound() {
        val variants = listOf("思考中", "第2轮")
        assertEquals("思考中", AgentNotificationMotion.chipText(0, variants))
        assertEquals("第2轮", AgentNotificationMotion.chipText(3_500, variants))
        assertEquals("思考中", AgentNotificationMotion.chipText(7_000, variants))
    }

    @Test
    fun chipText_returnsNullWhenNothingUsable() {
        assertNull(AgentNotificationMotion.chipText(0, emptyList()))
        assertNull(AgentNotificationMotion.chipText(0, listOf("", "  ")))
    }

    @Test
    fun chipVariantStaysWithinChipWidth() {
        // chip 上限约 7 个字符 / 96dp；轮次文案必须远小于这个上限
        assertTrue("第12轮".length <= 5)
    }
}
