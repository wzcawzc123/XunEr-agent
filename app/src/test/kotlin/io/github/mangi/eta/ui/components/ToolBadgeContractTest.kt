package io.github.mangi.eta.ui.components

import io.github.mangi.eta.agent.plan.AgentPlanPhase
import io.github.mangi.eta.ui.model.ToolActivityStatusUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 工具徽标的状态契约：**运行中呼吸、失败转红、完成变灰**。
 *
 * 背景（真机反馈）：原有工具的徽标"有呼吸效果和完成后颜色的变化"，
 * 担心新加的工具体验不一致。徽标本身在 Composable 里，用两层测试锁住：
 * 1. [nodeTone] 是纯函数——直接断言状态到色调的映射；
 * 2. 源码断言——呼吸动画必须由 `tone == Running` 驱动，而不是写死/按工具名判断。
 *
 * 这样后面加工具只要走同一条渲染路径，行为就一致；谁把呼吸改成常开或写死，测试立刻红。
 */
class ToolBadgeContractTest {

    private fun source(path: String): String {
        val candidates = listOf(File(path), File("app/$path"))
        val file = candidates.firstOrNull { it.isFile }
            ?: error("找不到 $path，cwd=${File("").absolutePath}")
        return file.readText()
    }

    private val workProcess by lazy {
        source("src/main/kotlin/io/github/mangi/eta/ui/components/AgentWorkProcess.kt")
    }
    private val toolActivity by lazy {
        source("src/main/kotlin/io/github/mangi/eta/ui/components/AgentToolActivity.kt")
    }

    @Test
    fun `运行中映射到呼吸色调`() {
        assertEquals(
            "Running 必须产生呼吸色调，否则工具执行中没有视觉反馈",
            WorkNodeTone.Running,
            ToolActivityStatusUi.Running.nodeTone(),
        )
    }

    @Test
    fun `失败映射到错误色调`() {
        assertEquals(WorkNodeTone.Failed, ToolActivityStatusUi.Failed.nodeTone())
    }

    @Test
    fun `成功与未知都映射到完成色调`() {
        assertEquals(WorkNodeTone.Done, ToolActivityStatusUi.Success.nodeTone())
        assertEquals(WorkNodeTone.Done, ToolActivityStatusUi.Unknown.nodeTone())
    }

    @Test
    fun `呼吸动画由运行状态驱动而非写死`() {
        assertTrue(
            "徽标呼吸必须由 tone == Running 驱动（rememberDrawPhasePulse(active = …)）",
            workProcess.contains("rememberDrawPhasePulse(active = tone == WorkNodeTone.Running)"),
        )
    }

    @Test
    fun `状态色对运行与失败有区分`() {
        assertTrue(
            "运行态要有专属状态色（StatusRunning）",
            toolActivity.contains("ToolActivityStatusUi.Running -> StatusRunning"),
        )
        assertTrue(
            "失败态要转错误色（StatusError）",
            toolActivity.contains("ToolActivityStatusUi.Failed -> StatusError"),
        )
    }

    @Test
    fun `工具行标题在运行中启用微光`() {
        // ShimmerText(active = running)：运行中的工具标题有流动效果，与徽标呼吸同源。
        assertTrue(
            "工具行标题必须把 running 传给 ShimmerText",
            toolActivity.contains("ShimmerText(") && toolActivity.contains("active = running"),
        )
    }

    @Test
    fun `徽标渲染不按工具名分叉`() {
        // 回归点：若有人按工具名给新工具单独画徽标，就会重新出现"新旧不一致"。
        assertTrue(
            "徽标只能由 WorkNodeBadge(icon, tone) 统一渲染",
            workProcess.contains("WorkNodeBadge(icon = icon, tone = tone)"),
        )
        // 计划阶段只是交互姿态，不参与徽标渲染（避免误以为要按 phase 分叉）。
        assertTrue("徽标渲染不得依赖计划阶段", !workProcess.contains("AgentPlanPhase"))
    }
}
