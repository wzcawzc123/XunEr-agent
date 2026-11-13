package io.github.mangi.eta.agent.memory

import io.github.mangi.eta.data.repository.AgentMemorySnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 水位条的口径要求只有一条：**与注入侧逐字符一致**。
 *
 * 模型看到的核心区，必须和用户在水位条上看到的数字对得上；两侧各写一套规则是历史上
 * 真实出过的问题（预览认的行和注入认的不是同一行），而且两侧都不报错，只会让人怀疑记忆没生效。
 */
class AgentMemoryPreviewTest {

    @Test
    fun coreCharsCountsOnlyCoreSection() {
        val content = "# 核心记忆\n- 甲\n# 归档\n- 乙乙乙\n"
        // 核心区 = "# 核心记忆" + 一个换行 + "- 甲"
        assertEquals(
            "# 核心记忆".length + 1 + "- 甲".length,
            AgentMemoryPreview.coreChars(content),
        )
        assertEquals(0, AgentMemoryPreview.coreChars("# 归档\n- 乙"))
        assertEquals(0, AgentMemoryPreview.coreChars(""))
    }

    @Test
    fun coreCharsSumsDuplicateSections() {
        val content = "# 核心记忆\n- 一\n# 归档\nx\n# 核心记忆\n- 二\n"
        val injected = AgentMemoryContextBuilder.build(snapshot(content), 128_000)
        // 与注入侧长度严格一致：水位条显示的数字，就是模型实际拿到的那段字符数
        //（包含行尾换行与末尾空行，两侧必须同口径）。
        assertEquals(injected.coreContent.length, AgentMemoryPreview.coreChars(content))
        assertTrue(AgentMemoryPreview.coreChars(content) > 0)
    }

    @Test
    fun coreCharsMatchesInjectionForMultiSectionContent() {
        val content = """
            |# 核心记忆
            |- 用户偏好中文
            |## 子节也算核心区
            |细节
            |
            |# 归档
            |不该出现在核心区
            |
            |# 核心记忆
            |- 第二个核心段
        """.trimMargin()

        val injected = AgentMemoryContextBuilder.build(snapshot(content), 128_000).coreContent
        assertEquals(injected.length, AgentMemoryPreview.coreChars(content))
        assertTrue(injected.contains("- 第二个核心段"))
    }

    private fun snapshot(content: String): AgentMemorySnapshot = AgentMemorySnapshot(
        content = content,
        revision = "a".repeat(64),
        byteSize = content.toByteArray(Charsets.UTF_8).size,
        lineCount = content.lines().size,
    )
}
