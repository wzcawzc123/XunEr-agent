package io.github.mangi.eta.agent.plan

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPlanModeToolTest {

    private val conversationId = "conv-1"

    private class Fixture {
        val planDao = FakePlanDao()
        val todoDao = FakeTodoDao()
        val modeStore = AgentPlanModeStore(planDao)
        val planStore = AgentPlanStore(todoDao)
    }

    private fun exitArgs(
        markdown: String = "# 计划\n先只读调研，再执行。",
        vararg steps: Pair<String, String?>,
    ): JSONObject {
        val array = JSONArray()
        steps.forEach { (subject, status) ->
            array.put(
                JSONObject().put("subject", subject).apply {
                    if (status != null) put("status", status)
                },
            )
        }
        return JSONObject().put("plan", markdown).put("steps", array)
    }

    @Test
    fun `进入计划模式会切换到只读并清空旧清单`() {
        val f = Fixture()
        AgentPlanTool.execute(
            JSONObject().put("items", JSONArray().put(JSONObject().put("subject", "旧任务"))),
            f.planStore,
            conversationId,
            1L,
        )

        val result = AgentPlanModeTool.enter(f.modeStore, f.planStore, conversationId, 2L)

        assertTrue(result.contains("已进入计划模式"))
        assertEquals(AgentPlanPhase.PLANNING, f.modeStore.phaseBlocking(conversationId))
        assertTrue("新计划不应继承旧清单", f.planStore.loadBlocking(conversationId).isEmpty())
    }

    @Test
    fun `不在计划模式时不能提交计划`() {
        val f = Fixture()
        val result = AgentPlanModeTool.exit(
            exitArgs(steps = arrayOf("调研" to null)),
            f.modeStore,
            f.planStore,
            conversationId,
            1L,
        )
        assertTrue(result.startsWith("失败"))
        assertTrue(result.contains("不在计划模式"))
        assertTrue(f.planStore.loadBlocking(conversationId).isEmpty())
    }

    @Test
    fun `提交计划后进入待批准并写入全 pending 步骤`() {
        val f = Fixture()
        AgentPlanModeTool.enter(f.modeStore, f.planStore, conversationId, 1L)

        val result = AgentPlanModeTool.exit(
            exitArgs(steps = arrayOf("调研现状" to null, "给出方案" to null)),
            f.modeStore,
            f.planStore,
            conversationId,
            2L,
        )

        assertTrue("实际回执：$result", result.contains("等待用户批准"))
        assertTrue(result.contains("计划 v1"))
        val state = f.modeStore.getBlocking(conversationId)!!
        assertEquals(AgentPlanPhase.REVIEW, state.phase)
        assertEquals(1, state.version)
        assertEquals("# 计划\n先只读调研，再执行。", state.markdown)
        val items = f.planStore.loadBlocking(conversationId)
        assertEquals(listOf("调研现状", "给出方案"), items.map { it.subject })
        assertTrue(items.all { it.status == AgentPlanStatus.PENDING })
    }

    @Test
    fun `计划步骤不允许预先标为已完成`() {
        val f = Fixture()
        AgentPlanModeTool.enter(f.modeStore, f.planStore, conversationId, 1L)

        val result = AgentPlanModeTool.exit(
            exitArgs(steps = arrayOf("调研" to "completed")),
            f.modeStore,
            f.planStore,
            conversationId,
            2L,
        )

        assertTrue(result.startsWith("失败"))
        assertTrue(result.contains("必须是 pending"))
        assertEquals(AgentPlanPhase.PLANNING, f.modeStore.phaseBlocking(conversationId))
        assertTrue(f.planStore.loadBlocking(conversationId).isEmpty())
    }

    @Test
    fun `空步骤与超长计划都被拒绝`() {
        val f = Fixture()
        AgentPlanModeTool.enter(f.modeStore, f.planStore, conversationId, 1L)

        assertTrue(
            AgentPlanModeTool.exit(
                exitArgs(steps = emptyArray<Pair<String, String?>>()),
                f.modeStore,
                f.planStore,
                conversationId,
                2L,
            ).contains("steps 不能为空"),
        )
        assertTrue(
            AgentPlanModeTool.exit(
                exitArgs(markdown = "x".repeat(AgentPlanModeTool.MAX_PLAN_CHARS + 1), steps = arrayOf("调研" to null)),
                f.modeStore,
                f.planStore,
                conversationId,
                3L,
            ).contains("超过"),
        )
    }

    @Test
    fun `执行中重复进入计划模式不会重置当前计划`() {
        val f = Fixture()
        AgentPlanModeTool.enter(f.modeStore, f.planStore, conversationId, 1L)
        AgentPlanModeTool.exit(exitArgs(steps = arrayOf("调研" to null)), f.modeStore, f.planStore, conversationId, 2L)
        f.modeStore.approveBlocking(conversationId, 3L)

        val result = AgentPlanModeTool.enter(f.modeStore, f.planStore, conversationId, 4L)

        assertTrue(result.contains("正在执行"))
        assertEquals(AgentPlanPhase.EXECUTING, f.modeStore.phaseBlocking(conversationId))
        assertEquals(1, f.planStore.loadBlocking(conversationId).size)
    }
}
