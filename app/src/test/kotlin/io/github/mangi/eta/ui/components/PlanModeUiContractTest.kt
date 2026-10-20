package io.github.mangi.eta.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 计划模式的**入口与呈现约定**测试（纯源码断言）。
 *
 * 背景（v3.13.8 真机反馈）：入口曾做成输入栏那一排里的文字 pill，
 * 与该排统一尺寸的图标按钮形态不一致，挤占空间且高度不齐；计划正文又塞在
 * 审批条里做"三行截断 + 点击展开"，展开后没有滚动容器，长计划直接被裁剪。
 *
 * 这些是结构不变量，用源码断言锁死比引入 Compose UI 测试基建划算得多：
 * - 入口必须在加号菜单 + 输入区可删除 chip，不能回到输入栏那一排；
 * - 计划正文必须走 Markdown 渲染，且不受工具卡的行数截断。
 */
class PlanModeUiContractTest {

    private fun source(path: String): String {
        val candidates = listOf(File(path), File("app/$path"))
        val file = candidates.firstOrNull { it.isFile }
            ?: error("找不到 $path，cwd=${File("").absolutePath}")
        return file.readText()
    }

    private val inputBar by lazy {
        source("src/main/kotlin/io/github/mangi/eta/ui/components/AgentChatInputBar.kt")
    }
    private val attachments by lazy {
        source("src/main/kotlin/io/github/mangi/eta/ui/components/AgentChatFileAttachments.kt")
    }
    private val toolActivity by lazy {
        source("src/main/kotlin/io/github/mangi/eta/ui/components/AgentToolActivity.kt")
    }

    @Test
    fun `输入栏不再有计划模式 pill`() {
        assertFalse(
            "输入栏那一排必须是统一的图标按钮，不能再插带文字的 PlanModeChip",
            inputBar.contains("PlanModeChip"),
        )
    }

    @Test
    fun `入口在加号菜单里`() {
        assertTrue(
            "加号菜单必须有「计划 / 退出计划」项",
            attachments.contains("chat_plan_mode_enter") &&
                attachments.contains("chat_plan_mode_exit"),
        )
    }

    @Test
    fun `输入区用可删除 chip 表示计划状态`() {
        assertTrue("需要 PlanModeStrip 组件", attachments.contains("fun PlanModeStrip("))
        assertTrue("输入栏要渲染它", inputBar.contains("PlanModeStrip("))
        // chip 必须有删除入口：点 × 即退出计划模式。
        assertTrue(
            "chip 上必须有可点击的删除按钮",
            attachments.contains("onRemove") || attachments.contains("onClick = onRemove"),
        )
    }

    @Test
    fun `REVIEW 阶段不显示可点击的退出 chip`() {
        // 回归点：曾用 `planPhase != NORMAL` 控制 chip 显示，而 toggleAction(REVIEW) = NONE，
        // 结果待批准阶段出现一个点了没反应的 ×。显示条件必须与 toggle 语义同源。
        assertTrue(
            "chip 的可见性必须走 showsToggle，而不是直接比较 phase",
            inputBar.contains("AgentPlanModeEntry.showsToggle(planPhase)"),
        )
    }

    @Test
    fun `菜单里的计划项在待批准阶段不可点`() {
        assertTrue("菜单项需要 planActionable 守卫", attachments.contains("planActionable"))
        assertTrue(
            "待批准时文案要说明原因，而不是给一个点了没反应的入口",
            attachments.contains("chat_plan_mode_pending"),
        )
    }

    @Test
    fun `执行期间不再显示审批条`() {
        // 回归点：批准后阶段在运行时转 EXECUTING，但 UI 的 planPhase 要等收尾才追上；
        // 整个执行期审批条都挂着"等待你确认"，用户会以为批准没生效（真机实测）。
        assertTrue(
            "审批条可见性必须排除 isStreaming",
            inputBar.contains("showsApproval(planPhase)") && inputBar.contains("!isStreaming"),
        )
    }

    @Test
    fun `审批条不再承载计划正文`() {
        assertFalse(
            "审批条不能再用三行截断展示正文（展开后无滚动容器会裁剪）",
            inputBar.contains("maxLines = if (expanded)"),
        )
        assertFalse("审批条不应再接收 preview", inputBar.contains("preview: String?"))
    }

    @Test
    fun `计划正文走 Markdown 渲染`() {
        assertTrue(
            "工具卡里的计划正文必须交给 StaticMarkdown，与聊天气泡同款",
            toolActivity.contains("AgentPlanReceipt.parse") &&
                toolActivity.contains("StaticMarkdown("),
        )
    }
}
