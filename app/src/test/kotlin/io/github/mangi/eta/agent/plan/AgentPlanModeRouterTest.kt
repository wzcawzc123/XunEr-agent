package io.github.mangi.eta.agent.plan

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPlanModeRouterTest {

    private val conversationId = "conv-1"

    private class Fixture {
        val planDao = FakePlanDao()
        val todoDao = FakeTodoDao()
        val modeStore = AgentPlanModeStore(planDao)
        val planStore = AgentPlanStore(todoDao)
    }

    private fun Fixture.toReview() {
        modeStore.enterPlanningBlocking(conversationId, "plan-1", 1L)
        modeStore.submitBlocking(conversationId, "# 计划", 2L)
        planStore.replaceBlocking(
            conversationId,
            listOf(
                AgentPlanItem.create(0, "s1", "调研", null, AgentPlanStatus.PENDING).getOrThrow(),
                AgentPlanItem.create(1, "s2", "执行", null, AgentPlanStatus.PENDING).getOrThrow(),
            ),
            2L,
        )
    }

    @Test
    fun `普通对话原样返回`() {
        val f = Fixture()
        val routed = AgentPlanModeRouter.route("你好", conversationId, f.modeStore, f.planStore, 1L)
        assertEquals("你好", routed.prompt)
        assertEquals(AgentPlanPhase.NORMAL, routed.phase)
    }

    @Test
    fun `没有会话标识时不参与计划路由`() {
        val f = Fixture()
        val routed = AgentPlanModeRouter.route("/plan 调研", null, f.modeStore, f.planStore, 1L)
        assertEquals("/plan 调研", routed.prompt)
        assertEquals(AgentPlanPhase.NORMAL, routed.phase)
    }

    @Test
    fun `斜杠 plan 进入计划模式并清空旧清单`() {
        val f = Fixture()
        f.planStore.replaceBlocking(
            conversationId,
            listOf(AgentPlanItem.create(0, "old", "旧任务", null, null).getOrThrow()),
            1L,
        )

        val routed = AgentPlanModeRouter.route("/plan 帮我调研登录流程", conversationId, f.modeStore, f.planStore, 2L)

        assertEquals(AgentPlanPhase.PLANNING, routed.phase)
        assertEquals("帮我调研登录流程", routed.prompt)
        assertTrue(f.planStore.loadBlocking(conversationId).isEmpty())
    }

    @Test
    fun `planner 不是 plan 命令`() {
        val f = Fixture()
        val routed = AgentPlanModeRouter.route("/planner 调研", conversationId, f.modeStore, f.planStore, 1L)
        assertEquals(AgentPlanPhase.NORMAL, routed.phase)
        assertEquals("/planner 调研", routed.prompt)
    }

    @Test
    fun `按钮进入规划后用户消息保持规划阶段且不加前缀`() {
        // 输入栏 pill 是主要入口：它先写库，用户再发任务。
        // 这条路径不经过 "/plan"，Router 必须原样透传消息并保持 PLANNING。
        val f = Fixture()
        f.modeStore.enterPlanningBlocking(conversationId, "plan-ui-1", 1L)

        val routed = AgentPlanModeRouter.route(
            "帮我调研一下通知权限怎么开",
            conversationId,
            f.modeStore,
            f.planStore,
            2L,
        )

        assertEquals(AgentPlanPhase.PLANNING, routed.phase)
        assertEquals("帮我调研一下通知权限怎么开", routed.prompt)
    }

    @Test
    fun `斜杠 plan off 退出并清空清单`() {
        val f = Fixture()
        f.toReview()
        val routed = AgentPlanModeRouter.route("/plan off", conversationId, f.modeStore, f.planStore, 3L)
        assertEquals(AgentPlanPhase.NORMAL, routed.phase)
        assertTrue(routed.prompt.contains("退出"))
        assertTrue(f.planStore.loadBlocking(conversationId).isEmpty())
    }

    @Test
    fun `待批准时回复批准进入执行`() {
        val f = Fixture()
        f.toReview()
        val routed = AgentPlanModeRouter.route("批准", conversationId, f.modeStore, f.planStore, 3L)
        assertEquals(AgentPlanPhase.EXECUTING, routed.phase)
        assertTrue(routed.prompt.contains("已批准"))
        assertEquals(1, f.modeStore.getBlocking(conversationId)!!.approvedVersion)
        // 批准后给出明确起点：第一条 pending 自动变成 in_progress。
        assertEquals(AgentPlanStatus.IN_PROGRESS, f.planStore.loadBlocking(conversationId).first().status)
    }

    @Test
    fun `待批准时回复取消放弃并清空清单`() {
        val f = Fixture()
        f.toReview()
        val routed = AgentPlanModeRouter.route("取消", conversationId, f.modeStore, f.planStore, 3L)
        assertEquals(AgentPlanPhase.NORMAL, routed.phase)
        assertTrue(routed.prompt.contains("取消"))
        assertTrue(f.planStore.loadBlocking(conversationId).isEmpty())
    }

    @Test
    fun `待批准时其他内容视为修改意见`() {
        val f = Fixture()
        f.toReview()
        val routed = AgentPlanModeRouter.route("第二步改成先备份", conversationId, f.modeStore, f.planStore, 3L)
        assertEquals(AgentPlanPhase.PLANNING, routed.phase)
        assertTrue(routed.prompt.contains("修改意见"))
        assertTrue(routed.prompt.contains("第二步改成先备份"))
    }

    @Test
    fun `像批准但不是批准的短句不会被误判`() {
        val f = Fixture()
        f.toReview()
        val routed = AgentPlanModeRouter.route("好像还缺回滚方案", conversationId, f.modeStore, f.planStore, 3L)
        assertEquals(AgentPlanPhase.PLANNING, routed.phase)
        assertTrue(routed.prompt.contains("修改意见"))
    }

    @Test
    fun `以执行开头的修改意见不会被当成批准`() {
        // 回归点：前缀匹配时代，`startsWith("执行")` + 长度 ≤12 会把这句话判成批准，
        // 直接进入 EXECUTING 并开始改设备。
        val f = Fixture()
        f.toReview()
        val routed = AgentPlanModeRouter.route("执行前先确认备份", conversationId, f.modeStore, f.planStore, 3L)
        assertEquals(
            "修改意见必须留在 PLANNING，不能被当成批准",
            AgentPlanPhase.PLANNING,
            routed.phase,
        )
    }

    @Test
    fun `以取消开头的修改意见不会被当成取消计划`() {
        val f = Fixture()
        f.toReview()
        val routed = AgentPlanModeRouter.route("取消按钮的颜色改成红色", conversationId, f.modeStore, f.planStore, 3L)
        assertEquals(
            "这是修改意见，不是放弃计划",
            AgentPlanPhase.PLANNING,
            routed.phase,
        )
        assertTrue(routed.prompt.contains("修改意见"))
    }

    @Test
    fun `批准并执行这类明确整句仍然生效`() {
        val f = Fixture()
        f.toReview()
        val routed = AgentPlanModeRouter.route("批准并执行", conversationId, f.modeStore, f.planStore, 3L)
        assertEquals(AgentPlanPhase.EXECUTING, routed.phase)
    }

    @Test
    fun `执行中 plan 不会静默丢弃已批准计划`() {
        val f = Fixture()
        f.toReview()
        f.modeStore.approveBlocking(conversationId, 3L)
        val routed = AgentPlanModeRouter.route("/plan 重新来", conversationId, f.modeStore, f.planStore, 4L)
        assertEquals(AgentPlanPhase.EXECUTING, routed.phase)
        assertTrue(routed.prompt.contains("确认"))
        assertEquals(1, f.modeStore.getBlocking(conversationId)!!.approvedVersion)
    }

    @Test
    fun `规划中回复取消直接退出`() {
        val f = Fixture()
        f.modeStore.enterPlanningBlocking(conversationId, "plan-1", 1L)
        f.planStore.replaceBlocking(
            conversationId,
            listOf(AgentPlanItem.create(0, "s1", "调研", null, null).getOrThrow()),
            1L,
        )
        val routed = AgentPlanModeRouter.route("取消", conversationId, f.modeStore, f.planStore, 2L)
        assertEquals(AgentPlanPhase.NORMAL, routed.phase)
        assertTrue(f.planStore.loadBlocking(conversationId).isEmpty())
    }

    @Test
    fun `规划中还没提交时不能批准`() {
        val f = Fixture()
        f.modeStore.enterPlanningBlocking(conversationId, "plan-1", 1L)
        val routed = AgentPlanModeRouter.route("批准", conversationId, f.modeStore, f.planStore, 2L)
        assertEquals(AgentPlanPhase.PLANNING, routed.phase)
        assertTrue(routed.prompt.contains("尚未提交"))
    }

    @Test
    fun `执行中回复取消会中止并回到普通对话`() {
        val f = Fixture()
        f.toReview()
        f.modeStore.approveBlocking(conversationId, 3L)
        val routed = AgentPlanModeRouter.route("取消", conversationId, f.modeStore, f.planStore, 4L)
        assertEquals(AgentPlanPhase.NORMAL, routed.phase)
        assertTrue(routed.prompt.contains("中止执行"))
        assertTrue(f.planStore.loadBlocking(conversationId).isEmpty())
    }
}
