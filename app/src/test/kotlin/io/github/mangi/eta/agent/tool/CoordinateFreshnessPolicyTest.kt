package io.github.mangi.eta.agent.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoordinateFreshnessPolicyTest {
    private fun verdict(
        observedVersion: Long,
        currentVersion: Long,
        observedPackage: String? = "me.example.app",
        currentPackage: String? = "me.example.app",
    ) = CoordinateFreshnessPolicy.evaluate(observedVersion, currentVersion, observedPackage, currentPackage)

    @Test
    fun noScrollAndSameWindowAllows() {
        assertEquals(CoordinateFreshnessPolicy.Verdict.ALLOW, verdict(3, 3))
    }

    @Test
    fun scrollingAfterObserveMakesCoordinatesStale() {
        assertEquals(CoordinateFreshnessPolicy.Verdict.STALE_AFTER_SCROLL, verdict(3, 4))
    }

    @Test
    fun switchingAppMakesCoordinatesStale() {
        assertEquals(
            CoordinateFreshnessPolicy.Verdict.STALE_WINDOW,
            verdict(3, 3, "me.example.app", "com.other.app"),
        )
    }

    @Test
    fun withoutAnyObservationCoordinatesAreAllowed() {
        assertEquals(CoordinateFreshnessPolicy.Verdict.ALLOW, verdict(-1, 99))
    }

    @Test
    fun missingPackagesSkipTheWindowCheck() {
        assertEquals(CoordinateFreshnessPolicy.Verdict.ALLOW, verdict(3, 3, null, "me.example.app"))
        assertEquals(CoordinateFreshnessPolicy.Verdict.ALLOW, verdict(3, 3, "me.example.app", null))
    }

    @Test
    fun resolutionSwitchMakesCoordinatesStale() {
        // 真机实测：内存管理模块会话横跨 FHD↔2K 切换，旧坐标系统性点偏。
        assertEquals(
            CoordinateFreshnessPolicy.Verdict.STALE_RESOLUTION,
            CoordinateFreshnessPolicy.evaluate(
                observedVersion = 3,
                currentVersion = 3,
                observedPackage = "me.example.app",
                currentPackage = "me.example.app",
                observedScreen = 1080 to 2412,
                currentScreen = 1440 to 3216,
            ),
        )
    }

    @Test
    fun sameResolutionAllows() {
        assertEquals(
            CoordinateFreshnessPolicy.Verdict.ALLOW,
            CoordinateFreshnessPolicy.evaluate(
                observedVersion = 3,
                currentVersion = 3,
                observedPackage = "me.example.app",
                currentPackage = "me.example.app",
                observedScreen = 1440 to 3216,
                currentScreen = 1440 to 3216,
            ),
        )
    }

    @Test
    fun missingScreenSizeSkipsTheResolutionCheck() {
        assertEquals(
            CoordinateFreshnessPolicy.Verdict.ALLOW,
            CoordinateFreshnessPolicy.evaluate(3, 3, "me.example.app", "me.example.app", null, 1440 to 3216),
        )
        assertEquals(
            CoordinateFreshnessPolicy.Verdict.ALLOW,
            CoordinateFreshnessPolicy.evaluate(3, 3, "me.example.app", "me.example.app", 1080 to 2412, null),
        )
    }

    @Test
    fun resolutionMessageTellsModelToReobserve() {
        assertTrue(
            CoordinateFreshnessPolicy.message(CoordinateFreshnessPolicy.Verdict.STALE_RESOLUTION)
                .contains("重新 observe_screen"),
        )
    }

    @Test
    fun messagesTellTheModelExactlyWhatToDo() {
        assertTrue(CoordinateFreshnessPolicy.message(CoordinateFreshnessPolicy.Verdict.STALE_AFTER_SCROLL).contains("重新 observe_screen"))
        assertTrue(CoordinateFreshnessPolicy.message(CoordinateFreshnessPolicy.Verdict.STALE_WINDOW).contains("重新 observe_screen"))
        assertEquals("", CoordinateFreshnessPolicy.message(CoordinateFreshnessPolicy.Verdict.ALLOW))
    }
}
