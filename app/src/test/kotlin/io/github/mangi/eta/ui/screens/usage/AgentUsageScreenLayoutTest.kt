package io.github.mangi.eta.ui.screens.usage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 用量统计页的**页面骨架约定**测试。
 *
 * 背景（v3.13.9 真机反馈）：这页原来是全 App 唯一不走 [MiuixScaffold] 的页面 ——
 * 标题自己写了个左对齐 `Text`，于是标题位置、字号、TopAppBar 高度、返回键全都不见，
 * 与记忆页/角色页（标题在统一的 `AdaptiveTopAppBar` 里、居中）观感割裂。
 *
 * 这类"约定被某次改动悄悄破坏"的问题，UI 上看一眼就能发现、却没有自动化兜底。
 * 项目目前没有 Compose UI 测试基建（无 `createComposeRule`），引入它的成本远高于收益；
 * 而这些约定恰恰是**结构不变量**，用源码断言锁死的性价比最高 ——
 * 它不校验渲染结果，只校验"标题必须走统一骨架、必须来自字符串资源、必须保留返回入口"。
 *
 * 若将来接入 Compose UI 测试，可用 `onNodeWithText("用量统计")` 的语义断言替换本文件。
 */
class AgentUsageScreenLayoutTest {

    private val source: String by lazy {
        val relative = File("src/main/kotlin/io/github/mangi/eta/ui/screens/usage/AgentUsageScreen.kt")
        val fromModuleRoot = File("app/src/main/kotlin/io/github/mangi/eta/ui/screens/usage/AgentUsageScreen.kt")
        val file = sequenceOf(relative, fromModuleRoot).firstOrNull { it.isFile }
            ?: error("找不到 AgentUsageScreen.kt，cwd=${File("").absolutePath}")
        file.readText()
    }

    @Test
    fun `标题走统一 Scaffold 而不是页面内自绘文本`() {
        assertTrue(
            "用量页必须使用 MiuixScaffold：标题、TopAppBar 高度与返回键都由它统一提供",
            source.contains("MiuixScaffold("),
        )
    }

    @Test
    fun `标题来自字符串资源`() {
        assertTrue(
            "标题应取 R.string.route_usage_stats，便于三套语言一致翻译",
            source.contains("R.string.route_usage_stats"),
        )
    }

    @Test
    fun `不再有裸的硬编码标题文本`() {
        // 回归点：曾经是 Text(text = "用量统计")，导致标题左对齐、与其他页割裂。
        assertFalse(
            "标题不应在页面内容里硬编码 —— 请交给 MiuixScaffold 的 TopAppBar",
            source.contains("""text = "用量统计"""),
        )
    }

    @Test
    fun `保留返回入口`() {
        assertTrue(
            "与其他页面一致，用量页需要 onBack（TopAppBar 返回键）",
            source.contains("onBack"),
        )
    }

    @Test
    fun `内容区套用统一边距与导航栏避让`() {
        // 与记忆页同款：horizontalCutoutPadding + sidePadding + navigationBarsPadding。
        // 少任何一项都会让这页的左右留白/底部避让和其他页不一致。
        assertTrue(source.contains("horizontalCutoutPadding()"))
        assertTrue(source.contains("padding(horizontal = sidePadding)"))
        assertTrue(source.contains("navigationBarsPadding()"))
    }
}
