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
    fun messagesTellTheModelExactlyWhatToDo() {
        assertTrue(CoordinateFreshnessPolicy.message(CoordinateFreshnessPolicy.Verdict.STALE_AFTER_SCROLL).contains("重新 observe_screen"))
        assertTrue(CoordinateFreshnessPolicy.message(CoordinateFreshnessPolicy.Verdict.STALE_WINDOW).contains("重新 observe_screen"))
        assertEquals("", CoordinateFreshnessPolicy.message(CoordinateFreshnessPolicy.Verdict.ALLOW))
    }
}
