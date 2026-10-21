package io.github.mangi.eta.ui.components

import io.github.mangi.eta.agent.tool.AgentToolRequirements
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具徽标图标的覆盖测试。
 *
 * 背景（真机反馈）：新加的计划/自动任务/技能工具，徽标落到了通用兜底图标上，
 * 与原有工具的专属徽标看起来"不是一套"。`iconForTool` 的 else 分支是静默兜底，
 * 不测的话每加一个工具就悄悄多一个"不一样的徽标"。
 *
 * 这里断言**每个登记工具都有专属图标**：新增工具漏配图标会立刻红。
 */
class ToolIconsCoverageTest {

    /** `run_command` 是 `terminal` 的别名（部分供应商的工具名），共用终端图标。 */
    private val aliases = setOf("run_command")

    @Test
    fun `每个登记工具都有专属图标`() {
        val names = AgentToolRequirements.toolNames - aliases
        assertTrue("登记表不能为空", names.isNotEmpty())
        val missing = names.filter { toolIconOrNull(it) == null }.sorted()
        assertTrue(
            "这些工具没有专属图标，会落到通用兜底徽标上：$missing",
            missing.isEmpty(),
        )
    }

    @Test
    fun `别名工具也能取到图标`() {
        // 别名不在登记表里，但模型真的会调用，徽标不能是兜底。
        assertNotNull("run_command 应共用 terminal 图标", toolIconOrNull("run_command"))
    }

    @Test
    fun `计划与自动任务工具有可辨识的图标`() {
        // 回归点：这几个工具首批接入时漏了图标映射。
        for (name in listOf(
            "enter_plan_mode", "exit_plan_mode", "todo_write",
            "create_auto_task", "list_auto_tasks", "delete_auto_task",
        )) {
            assertNotNull("$name 需要专属图标", toolIconOrNull(name))
        }
    }
}
