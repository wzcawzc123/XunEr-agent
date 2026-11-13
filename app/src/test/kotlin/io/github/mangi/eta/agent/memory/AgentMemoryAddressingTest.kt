package io.github.mangi.eta.agent.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 标题解析与位置建议的纯函数契约（不依赖 Android）。 */
class AgentMemoryAddressingTest {
    @Test
    fun parsesHeadingLevelsAndLineNumbers() {
        val headings = AgentMemoryHeadings.parse(
            listOf("# 核心记忆", "正文", "## 项目", "### 细节", "#### 太深不算", "## 待办"),
        )

        assertEquals(listOf(1, 3, 4, 6), headings.map { it.line })
        assertEquals(listOf(1, 2, 3, 2), headings.map { it.level })
        assertEquals(listOf("核心记忆", "项目", "细节", "待办"), headings.map { it.title })
        assertEquals("## 项目", headings[1].rendered)
    }

    @Test
    fun sectionRangeStopsAtSameOrHigherLevel() {
        val lines = listOf("# 核心记忆", "偏好", "## 待办", "- A", "### 子节", "- B", "## 环境", "Android")
        val headings = AgentMemoryHeadings.parse(lines)
        val todo = headings.indexOfFirst { it.title == "待办" }

        // 三级子节归父节；遇到同级的下一个标题才结束。
        assertEquals(3..6, AgentMemoryHeadings.sectionRange(headings, todo, lines.size))

        // 一级标题吃掉它下面的所有二三级子节（Markdown 语义），一直到最后一行。
        // 注入侧的 extractCore 也是这个口径：「# 核心记忆」包含它名下的 `##` 小节。
        val core = headings.indexOfFirst { it.title == "核心记忆" }
        assertEquals(1..8, AgentMemoryHeadings.sectionRange(headings, core, lines.size))
    }

    @Test
    fun matchesHeadingsIgnoringHashPrefixAndCase() {
        val headings = AgentMemoryHeadings.parse(listOf("## 项目", "## Project", "### 待办"))

        assertEquals(listOf(0), AgentMemoryHeadings.matchIndices(headings, "## 项目"))
        assertEquals(listOf(1), AgentMemoryHeadings.matchIndices(headings, "project"))
        assertEquals(emptyList<Int>(), AgentMemoryHeadings.matchIndices(headings, " "))
        assertEquals(emptyList<Int>(), AgentMemoryHeadings.matchIndices(headings, "不存在"))
    }

    @Test
    fun catalogueListsHeadingsForErrorMessages() {
        val headings = AgentMemoryHeadings.parse(listOf("## 项目", "## 待办"))

        val catalogue = AgentMemoryHeadings.catalogue(headings)

        assertTrue(catalogue.contains("第 1 行"))
        assertTrue(catalogue.contains("## 项目"))
        assertEquals("（没有任何标题）", AgentMemoryHeadings.catalogue(emptyList()))
    }
}
