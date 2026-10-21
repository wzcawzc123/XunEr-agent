package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.tool.AgentToolRequirements
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 「工具能力」页的覆盖测试：**登记表里的每个工具都必须展示**。
 *
 * 背景（真机反馈）：计划模式、自动任务、技能等新工具接入后，能力页完全没有它们——
 * 页面用的是一份手写静态列表，与 `AgentToolRequirements`（模型目录 + 执行边界的唯一合同）
 * 各写各的，加工具时很容易只改一边。用户看到的"能力清单"因此比模型真实能力少 26 个。
 *
 * 现在用测试把两边钉住：新增工具若忘记在能力页登记，这里立刻红。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentToolsCatalogCoverageTest {

    /**
     * 不单独展示的条目：
     * - `run_command`：`terminal` 的别名（部分供应商的工具名），同一能力不重复占位；
     * - `browser_read` / `browser_interact` / `browser_screenshot`：浏览器操作卡的展示 id，
     *   经 `actualToolName` 映射回 `browser_use`，不是独立工具。
     */
    private val notSeparatelyListed = setOf(
        "run_command",
        "browser_read", "browser_interact", "browser_screenshot",
    )

    @Test
    fun `每个登记工具都在能力页展示`() {
        val state = buildToolsState(RuntimeEnvironment.getApplication())
        val displayed = state.groups.flatMap { group -> group.tools }.map { it.id }.toSet()
        val expected = AgentToolRequirements.toolNames - notSeparatelyListed
        val missing = (expected - displayed).sorted()
        assertTrue(
            "这些工具已登记但能力页没有展示（用户看不到模型真实能力）：$missing",
            missing.isEmpty(),
        )
    }

    @Test
    fun `能力页不会展示未登记的工具`() {
        // 反向：页面上的 id 必须能在登记表里找到（浏览器别名经映射后命中 browser_use）。
        val state = buildToolsState(RuntimeEnvironment.getApplication())
        val aliases = setOf("browser_read", "browser_interact", "browser_screenshot")
        val unknown = state.groups.flatMap { group -> group.tools }
            .map { it.id }
            .filter { id -> id !in aliases && AgentToolRequirements.find(id) == null }
            .sorted()
        assertTrue("能力页展示了未登记的工具（toolCardRequirement 会抛异常）：$unknown", unknown.isEmpty())
    }

    @Test
    fun `分组标题与工具都非空`() {
        val state = buildToolsState(RuntimeEnvironment.getApplication())
        state.groups.forEach { group ->
            assertTrue("分组 ${group.id} 标题不能为空", group.title.isNotBlank())
            assertTrue("分组 ${group.id} 不能为空", group.tools.isNotEmpty())
            group.tools.forEach { tool ->
                assertTrue("${tool.id} 标题不能为空", tool.title.isNotBlank())
                assertTrue("${tool.id} 说明不能为空", tool.summary.isNotBlank())
            }
        }
    }
}
