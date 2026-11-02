package io.github.mangi.eta.agent.memory

import io.github.mangi.eta.data.repository.AgentMemorySnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 预览切片的核心要求只有两条：
 *
 * 1. **只看核心区**——否则十几万字符的 MEMORY.md 会让 Compose 全量测量（真机白屏半秒以上）；
 * 2. **与注入侧同口径**——模型看到的核心区必须和用户预览到的一致，否则「记忆好像没生效」
 *    这类怀疑永远查不出原因（两侧都不报错，就是内容不同）。
 */
class AgentMemoryPreviewTest {

    @Test
    fun rendersCoreSectionAndSkipsArchive() {
        val content = """
            |# 核心记忆
            |- 用户偏好中文
            |- 项目叫 XunEr
            |
            |# 项目档案
            |很长的归档内容……
            |还有更多……
        """.trimMargin()

        val slice = AgentMemoryPreview.slice(content)
        assertTrue(slice.text.contains("# 核心记忆"))
        assertTrue(slice.text.contains("用户偏好中文"))
        assertFalse(slice.text.contains("项目档案"))
        assertFalse(slice.text.contains("很长的归档内容"))
        assertTrue(slice.truncated)
        assertTrue(slice.archivedChars > 0)
        assertEquals(1, slice.coreSectionCount)
    }

    @Test
    fun coreOnlyFileIsRenderedWhole() {
        val content = "# 核心记忆\n- 只有核心区\n"
        val slice = AgentMemoryPreview.slice(content)
        assertEquals(content, slice.text)
        assertFalse(slice.truncated)
        assertEquals(0, slice.archivedChars)
    }

    @Test
    fun fileWithoutCoreHeadingIsShownAsIs() {
        // 旧文件或用户自定义结构：不猜，原样给全文（安全降级）
        val content = "# 我的笔记\n随手记的东西\n"
        val slice = AgentMemoryPreview.slice(content)
        assertEquals(content, slice.text)
        assertFalse(slice.truncated)
        assertEquals(0, slice.coreSectionCount)
    }

    @Test
    fun allDuplicateCoreSectionsAreRendered() {
        // 历史遗留的重复标题：注入侧是合并注入的，预览只显示第一段会让用户
        // 以为第二段内容丢了。
        val content = """
            |# 核心记忆
            |- 第一段
            |
            |# 中间档案
            |归档
            |
            |# 核心记忆
            |- 第二段
        """.trimMargin()

        val slice = AgentMemoryPreview.slice(content)
        assertTrue(slice.text.contains("第一段"))
        assertTrue(slice.text.contains("第二段"))
        assertTrue(slice.text.contains("另一个同名"))
        assertEquals(2, slice.coreSectionCount)
    }

    @Test
    fun blankContentStaysEmpty() {
        val slice = AgentMemoryPreview.slice("")
        assertEquals("", slice.text)
        assertFalse(slice.truncated)
        assertEquals(0, slice.coreSectionCount)
    }

    /**
     * 口径一致性：预览显示的核心行，必须与注入侧交给模型的核心内容同源。
     * 两侧各写一套匹配规则是历史上真实出过的问题（预览认的行和注入认的不是同一行）。
     */
    @Test
    fun coreTextMatchesInjectionSide() {
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
        val preview = AgentMemoryPreview.slice(content)

        listOf("- 用户偏好中文", "## 子节也算核心区", "- 第二个核心段").forEach { line ->
            assertTrue("注入侧缺少：$line", injected.contains(line))
            assertTrue("预览缺少：$line", preview.text.contains(line))
        }
        listOf("不该出现在核心区").forEach { line ->
            assertFalse("注入侧不该有：$line", injected.contains(line))
            // 预览的归档提示里会出现「归档」字样，但不应出现正文
            assertFalse("预览不该有：$line", preview.text.contains(line))
        }
    }

    private fun snapshot(content: String): AgentMemorySnapshot = AgentMemorySnapshot(
        content = content,
        revision = "a".repeat(64),
        byteSize = content.toByteArray(Charsets.UTF_8).size,
        lineCount = content.lines().size,
    )
}
