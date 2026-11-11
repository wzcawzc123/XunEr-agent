package io.github.mangi.eta.agent.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 搜索的两条关键语义：
 *
 * 1. **total 是真实命中次数**，不受展示上限影响——用户看到的数字不该被 UI 上限篡改；
 * 2. **truncated 只看命中行数**——一行里命中三次不代表被截断。
 */
class AgentMemorySearchTest {

    private val content = """
        |# 核心记忆
        |- 用户偏好中文回答
        |- 项目名 XunEr
        |## 环境
        |- Android 平板，ColorOS
        |- Linux 环境未安装
        |# 归档
        |- 中文 中文 中文
    """.trimMargin()

    @Test
    fun findsSingleLine() {
        val result = AgentMemorySearch.search(content, "XunEr")
        assertEquals(1, result.total)
        assertEquals(1, result.matchedLines)
        assertEquals(3, result.hits.single().line)
        assertEquals("- 项目名 XunEr", result.hits.single().text)
        assertFalse(result.truncated)
    }

    @Test
    fun countsEveryOccurrenceOnSameLine() {
        val result = AgentMemorySearch.search(content, "中文")
        // 「偏好中文回答」一行 1 次，「中文 中文 中文」一行 3 次
        assertEquals(4, result.total)
        assertEquals(2, result.matchedLines)
        assertEquals(2, result.hits.size)
        val last = result.hits.last()
        assertEquals(3, last.ranges.size)
        // 行首是「- 」，所以第一个命中从下标 2 开始
        assertEquals(2..3, last.ranges.first())
    }

    @Test
    fun ignoresCase() {
        val result = AgentMemorySearch.search(content, "xuner")
        assertEquals(1, result.total)
        assertEquals("- 项目名 XunEr", result.hits.single().text)
    }

    @Test
    fun blankQueryFindsNothing() {
        assertEquals(0, AgentMemorySearch.search(content, "  ").total)
        assertEquals(0, AgentMemorySearch.search(content, "").total)
        assertEquals(0, AgentMemorySearch.search("", "x").total)
    }

    @Test
    fun limitCapsRowsButNotTotal() {
        val many = buildString {
            repeat(10) { appendLine("- 中文 第 $it 行") }
        }
        val result = AgentMemorySearch.search(many, "中文", limit = 3)
        assertEquals(10, result.total)
        assertEquals(10, result.matchedLines)
        assertEquals(3, result.hits.size)
        assertTrue(result.truncated)
        // 行号仍然是真实行号，不是展示序号
        assertEquals(listOf(1, 2, 3), result.hits.map { it.line })
    }

    @Test
    fun reportsTotalLines() {
        val result = AgentMemorySearch.search(content, "ColorOS")
        assertEquals(content.lines().size, result.totalLines)
        assertFalse(result.truncated)
    }
}
