package io.github.mangi.eta.agent.plan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 输入栏「计划」按钮的语义。
 *
 * 这些断言对应真机上的后果：点错一次就可能丢掉一份已写好的方案，
 * 所以状态映射必须由测试钉死，而不是散落在 Compose 条件里。
 */
class AgentPlanModeEntryTest {

    @Test
    fun `普通对话点击进入规划`() {
        assertEquals(
            AgentPlanModeEntry.ToggleAction.ENTER_PLANNING,
            AgentPlanModeEntry.toggleAction(AgentPlanPhase.NORMAL),
        )
    }

    @Test
    fun `规划中点击退出`() {
        assertEquals(
            AgentPlanModeEntry.ToggleAction.EXIT_PLANNING,
            AgentPlanModeEntry.toggleAction(AgentPlanPhase.PLANNING),
        )
    }

    @Test
    fun `执行中点击退出`() {
        assertEquals(
            AgentPlanModeEntry.ToggleAction.EXIT_PLANNING,
            AgentPlanModeEntry.toggleAction(AgentPlanPhase.EXECUTING),
        )
    }

    @Test
    fun `待批准时按钮不承担退出语义`() {
        // 关键回归：REVIEW 是唯一"已经产出了方案"的阶段，
        // 误触只能什么都不做，放弃必须走审批条上的明确按钮。
        assertEquals(
            AgentPlanModeEntry.ToggleAction.NONE,
            AgentPlanModeEntry.toggleAction(AgentPlanPhase.REVIEW),
        )
    }

    @Test
    fun `审批条只在待批准阶段出现`() {
        assertTrue(AgentPlanModeEntry.showsApproval(AgentPlanPhase.REVIEW))
        assertFalse(AgentPlanModeEntry.showsApproval(AgentPlanPhase.NORMAL))
        assertFalse(AgentPlanModeEntry.showsApproval(AgentPlanPhase.PLANNING))
        assertFalse(AgentPlanModeEntry.showsApproval(AgentPlanPhase.EXECUTING))
    }

    @Test
    fun `审批条出现时不重复显示计划按钮`() {
        // 两个入口同时出现会给出两个"取消"，用户无法判断哪个会丢数据。
        assertFalse(AgentPlanModeEntry.showsToggle(AgentPlanPhase.REVIEW))
        assertTrue(AgentPlanModeEntry.showsToggle(AgentPlanPhase.PLANNING))
        assertTrue(AgentPlanModeEntry.showsToggle(AgentPlanPhase.EXECUTING))
        assertTrue(AgentPlanModeEntry.showsToggle(AgentPlanPhase.NORMAL))
    }
}
