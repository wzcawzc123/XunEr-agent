package io.github.mangi.eta.agent.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具目录挂载策略测试。
 *
 * 回归点：`planTools=false` 只能关掉任务清单工具，不能顺带关掉自动任务工具 ——
 * 两者共用 `AgentPlanToolCatalog`，但生命周期完全无关。
 */
class AgentPlanToolCatalogTest {

    private fun names(planTools: Boolean = true): List<String> {
        val tools = AgentToolCatalog.build(
            terminalTools = false,
            browserTools = false,
            planTools = planTools,
        )
        return (0 until tools.length()).map {
            tools.getJSONObject(it).getJSONObject("function").getString("name")
        }
    }

    @Test
    fun `默认挂载任务清单`() {
        val names = names()
        assertTrue(names.contains("todo_write"))
        assertTrue(names.contains("enter_plan_mode"))
        assertTrue(names.contains("exit_plan_mode"))
    }

    @Test
    fun `关闭清单不影响自动任务工具`() {
        val names = names(false)
        assertFalse(names.contains("todo_write"))
        assertFalse(names.contains("enter_plan_mode"))
        assertFalse(names.contains("exit_plan_mode"))
        assertTrue("自动任务工具不能因清单不可用而消失", names.contains("create_auto_task"))
        assertTrue(names.contains("list_auto_tasks"))
        assertTrue(names.contains("delete_auto_task"))
    }
}
