package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.model.AgentContextSnapshot
import io.github.mangi.eta.agent.model.AgentModelClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentCompactionDetailTest {
    private val summary = AgentModelClient.ConversationMessage("assistant", "摘要", contextSummary = true)

    @Test
    fun successWithSnapshotReportsShadowedCountAndCoveredTurns() {
        val snapshot = AgentContextSnapshot(operationId = "run", messages = listOf(summary), coveredUserTurns = 5)
        val detail = AgentCompactionDetail.forResult(ok = true, snapshot = snapshot, historySizeBefore = 12, error = null)
        assertTrue(detail.contains("已压缩 11 条历史"))
        assertTrue(detail.contains("覆盖 5 轮"))
    }

    @Test
    fun successWithoutSnapshotMeansNothingToCompact() {
        assertEquals(
            "上下文无需压缩",
            AgentCompactionDetail.forResult(ok = true, snapshot = null, historySizeBefore = 0, error = null),
        )
    }

    @Test
    fun failureShowsErrorOrFallback() {
        assertEquals(
            "摘要失败",
            AgentCompactionDetail.forResult(ok = false, snapshot = null, historySizeBefore = 0, error = "摘要失败"),
        )
        assertEquals(
            "上下文压缩失败",
            AgentCompactionDetail.forResult(ok = false, snapshot = null, historySizeBefore = 0, error = null),
        )
    }

    @Test
    fun shadowedCountNeverNegative() {
        // 压缩后消息不减少（保留尾段 + 摘要 ≥ 原历史）时不显示负数
        val snapshot = AgentContextSnapshot(operationId = "run", messages = listOf(summary), coveredUserTurns = 1)
        val detail = AgentCompactionDetail.forResult(ok = true, snapshot = snapshot, historySizeBefore = 1, error = null)
        assertTrue(detail.contains("已压缩 0 条历史"))
    }
}
