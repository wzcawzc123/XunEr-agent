package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.plan.AgentPlanModePolicy
import io.github.mangi.eta.agent.plan.AgentPlanPhase
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 只读阶段的**目录级**过滤：写工具根本不出现在模型可见的工具列表里。
 *
 * 回归点：真机实测中小模型看到 `todo_write` 就会去选它（即使提示词写明只读调研），
 * 被运行时拦截后仍不改。把写工具从目录里去掉，它就无从选错。
 */
class AgentToolCatalogPlanPhaseTest {

    private fun names(phase: AgentPlanPhase): List<String> {
        val tools: JSONArray = AgentToolCatalog.build(
            terminalTools = true,
            browserTools = true,
            planTools = true,
            planPhase = phase,
        )
        return (0 until tools.length()).map { index ->
            tools.getJSONObject(index).getJSONObject("function").getString("name")
        }
    }

    @Test
    fun `普通阶段保留写工具`() {
        val n = names(AgentPlanPhase.NORMAL)
        assertTrue("普通阶段应能看到任务清单写工具", "todo_write" in n)
        assertTrue("普通阶段应能看到终端", "terminal" in n)
        assertTrue("普通阶段应能看到计划提交", "exit_plan_mode" in n)
    }

    @Test
    fun `规划阶段不暴露任何写工具`() {
        val n = names(AgentPlanPhase.PLANNING)
        assertFalse("规划阶段不能把 todo_write 给模型看", "todo_write" in n)
        assertFalse("规划阶段不能把终端给模型看", "terminal" in n)
        assertFalse("规划阶段不能把写文件给模型看", "edit_file" in n)
        assertFalse("规划阶段不能把自动任务写给模型看", "create_auto_task" in n)
    }

    @Test
    fun `规划阶段保留只读调研与提交计划`() {
        val n = names(AgentPlanPhase.PLANNING)
        assertTrue("要能提交计划", "exit_plan_mode" in n)
        assertTrue("要能只读查文件", "glob_files" in n)
        assertTrue("要能读文件", "read_file" in n)
        assertTrue("browser_use 保留（动作级另有限制）", "browser_use" in n)
    }

    @Test
    fun `待批准阶段撤掉提交与重进入口`() {
        val review = names(AgentPlanPhase.REVIEW)
        assertFalse("待批准阶段不应再看到提交计划", "exit_plan_mode" in review)
        assertFalse("待批准阶段不应再看到进入计划", "enter_plan_mode" in review)
        assertFalse("待批准阶段同样不能写清单", "todo_write" in review)
        assertTrue("待批准阶段仍可只读查证", "glob_files" in review)
    }

    @Test
    fun `目录过滤与运行时白名单口径一致`() {
        // 目录里出现的每个工具，运行时也不能把它拦掉（否则模型会调用一个必然失败的工具）。
        val browserReadArgs = JSONObject().put("action", "read")
        for (phase in listOf(AgentPlanPhase.PLANNING, AgentPlanPhase.REVIEW)) {
            for (name in names(phase)) {
                val args = if (name == "browser_use") browserReadArgs else null
                assertFalse(
                    "$name 出现在目录里却会被运行时拦截，两处口径不一致",
                    AgentPlanModePolicy.isBlocked(phase, name, args),
                )
            }
        }
    }
}
