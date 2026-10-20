package io.github.mangi.eta.agent.plan

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPlanModeStoreTest {

    private val conversationId = "conv-1"

    @Test
    fun `没有记录时默认普通对话`() = runBlocking {
        val store = AgentPlanModeStore(FakePlanDao())
        assertEquals(AgentPlanPhase.NORMAL, store.phaseBlocking(conversationId))
        assertNull(store.get(conversationId))
    }

    @Test
    fun `进入计划模式后版本归零且进入只读`() = runBlocking {
        val store = AgentPlanModeStore(FakePlanDao())
        val state = store.enterPlanning(conversationId, "plan-1", 100L)
        assertEquals(AgentPlanPhase.PLANNING, state.phase)
        assertEquals(0, state.version)
        assertTrue(state.phase.isReadOnly)
        assertEquals("plan-1", store.get(conversationId)!!.planId)
    }

    @Test
    fun `提交计划递增版本并进入待批准`() = runBlocking {
        val store = AgentPlanModeStore(FakePlanDao())
        store.enterPlanning(conversationId, "plan-1", 100L)
        val v1 = store.submit(conversationId, "# 计划 v1", 200L)
        assertEquals(AgentPlanPhase.REVIEW, v1.phase)
        assertEquals(1, v1.version)
        assertNull(v1.approvedVersion)
        assertEquals("# 计划 v1", v1.markdown)

        val v2 = store.submit(conversationId, "# 计划 v2", 300L)
        assertEquals(2, v2.version)
        assertNull("新版本提交后旧批准必须失效", v2.approvedVersion)
    }

    @Test
    fun `批准绑定当前版本并进入执行`() = runBlocking {
        val store = AgentPlanModeStore(FakePlanDao())
        store.enterPlanning(conversationId, "plan-1", 100L)
        store.submit(conversationId, "# 计划", 200L)
        val approved = store.approve(conversationId, 300L)!!
        assertEquals(AgentPlanPhase.EXECUTING, approved.phase)
        assertEquals(1, approved.approvedVersion)
        assertTrue(approved.isExecutingApprovedVersion)
    }

    @Test
    fun `没有待批准计划时不能批准`() = runBlocking {
        val store = AgentPlanModeStore(FakePlanDao())
        assertNull(store.approve(conversationId, 100L))
        store.enterPlanning(conversationId, "plan-1", 100L)
        assertNull("PLANNING 没有可批准的版本", store.approve(conversationId, 200L))
    }

    @Test
    fun `修改意见回到规划并清空批准版本`() = runBlocking {
        val store = AgentPlanModeStore(FakePlanDao())
        store.enterPlanning(conversationId, "plan-1", 100L)
        store.submit(conversationId, "# 计划", 200L)
        store.approve(conversationId, 300L)
        val reopened = store.reopenPlanning(conversationId, 400L)!!
        assertEquals(AgentPlanPhase.PLANNING, reopened.phase)
        assertNull(reopened.approvedVersion)
    }

    @Test
    fun `取消计划回到普通对话但保留正文`() = runBlocking {
        val store = AgentPlanModeStore(FakePlanDao())
        store.enterPlanning(conversationId, "plan-1", 100L)
        store.submit(conversationId, "# 计划", 200L)
        val cancelled = store.cancel(conversationId, 300L)!!
        assertEquals(AgentPlanPhase.NORMAL, cancelled.phase)
        assertEquals("# 计划", cancelled.markdown)
        assertNull(cancelled.approvedVersion)
    }

    @Test
    fun `未知阶段回退普通对话`() {
        assertEquals(AgentPlanPhase.NORMAL, AgentPlanPhase.fromWire("bogus"))
        assertEquals(AgentPlanPhase.NORMAL, AgentPlanPhase.fromWire(null))
        assertEquals(AgentPlanPhase.PLANNING, AgentPlanPhase.fromWire(" PLANNING "))
    }

    @Test
    fun `执行阶段所有步骤结束才自动收尾`() {
        val completed = listOf(
            AgentPlanItem.create(0, "a", "A", null, AgentPlanStatus.COMPLETED).getOrThrow(),
            AgentPlanItem.create(1, "b", "B", null, AgentPlanStatus.CANCELLED).getOrThrow(),
        )
        val pending = listOf(
            AgentPlanItem.create(0, "a", "A", null, AgentPlanStatus.COMPLETED).getOrThrow(),
            AgentPlanItem.create(1, "b", "B", null, AgentPlanStatus.PENDING).getOrThrow(),
        )
        assertTrue(AgentPlanModeStore.shouldFinish(AgentPlanPhase.EXECUTING, completed))
        assertTrue(AgentPlanModeStore.shouldFinish(AgentPlanPhase.EXECUTING, emptyList()))
        assertTrue(!AgentPlanModeStore.shouldFinish(AgentPlanPhase.EXECUTING, pending))
        assertTrue(!AgentPlanModeStore.shouldFinish(AgentPlanPhase.NORMAL, completed))
    }

    @Test
    fun `执行期范围变化用当前正文重新走审批`() = runBlocking {
        val store = AgentPlanModeStore(FakePlanDao())
        store.enterPlanning(conversationId, "plan-1", 100L)
        store.submit(conversationId, "# 计划正文", 200L)
        val approved = store.approve(conversationId, 300L)!!
        assertEquals(AgentPlanPhase.EXECUTING, approved.phase)
        assertTrue(approved.isExecutingApprovedVersion)

        val reReview = store.reReviewBlocking(conversationId, 400L)!!

        assertEquals(AgentPlanPhase.REVIEW, reReview.phase)
        assertEquals("版本必须递增，旧批准不能继续生效", 2, reReview.version)
        assertNull(reReview.approvedVersion)
        assertEquals("# 计划正文", reReview.markdown)
    }

    @Test
    fun `没有正文时无法退回待批准`() = runBlocking {
        val store = AgentPlanModeStore(FakePlanDao())
        assertEquals(null, store.reReviewBlocking(conversationId, 100L))
    }

    @Test
    fun `主动放弃清空正文而文本取消保留`() = runBlocking {
        val store = AgentPlanModeStore(FakePlanDao())
        store.enterPlanning(conversationId, "plan-1", 100L)
        store.submit(conversationId, "# 计划正文", 200L)

        // 用户手打「取消」：历史里还留着那份计划，正文要保留供回看。
        val kept = store.cancel(conversationId, 300L)!!
        assertEquals(AgentPlanPhase.NORMAL, kept.phase)
        assertEquals("# 计划正文", kept.markdown)

        // UI 按钮主动放弃：这个计划不算数了，正文一起清，别变成下一份计划的干扰项。
        store.enterPlanning(conversationId, "plan-2", 400L)
        store.submit(conversationId, "# 新正文", 500L)
        val dropped = store.cancel(conversationId, 600L, clearMarkdown = true)!!
        assertEquals(AgentPlanPhase.NORMAL, dropped.phase)
        assertNull("UI 主动放弃必须清空正文", dropped.approvedVersion)
        assertEquals("", dropped.markdown)
    }

    @Test
    fun `注入范围随阶段与未完成项变化`() {
        // 计划结束后 markdown 与清单会保留供回看。若按"非 PLANNING 就注入"判断，
        // 之后的每一轮普通对话都会白背几千字旧计划 —— 这条锁住注入边界。
        assertEquals(AgentPlanInjectionScope.NONE, AgentPlanPhase.NORMAL.injectionScope(hasOpenWork = false))
        assertEquals(AgentPlanInjectionScope.CHECKLIST, AgentPlanPhase.NORMAL.injectionScope(hasOpenWork = true))
        // 规划阶段不注入：此时正在重做方案，旧清单会误导。
        assertEquals(AgentPlanInjectionScope.NONE, AgentPlanPhase.PLANNING.injectionScope(hasOpenWork = true))
        // 待批准必须注入正文，否则用户批准的东西模型自己都看不到。
        assertEquals(AgentPlanInjectionScope.PLAN_AND_CHECKLIST, AgentPlanPhase.REVIEW.injectionScope(hasOpenWork = false))
        assertEquals(AgentPlanInjectionScope.PLAN_AND_CHECKLIST, AgentPlanPhase.EXECUTING.injectionScope(hasOpenWork = true))
    }

    @Test
    fun `只有待批准与执行中存在用户可见计划`() {
        assertFalse(AgentPlanPhase.NORMAL.hasPlan)
        assertFalse(AgentPlanPhase.PLANNING.hasPlan)
        assertTrue(AgentPlanPhase.REVIEW.hasPlan)
        assertTrue(AgentPlanPhase.EXECUTING.hasPlan)
    }
}
