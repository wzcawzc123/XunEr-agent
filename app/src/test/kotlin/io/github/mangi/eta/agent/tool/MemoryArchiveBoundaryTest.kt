package io.github.mangi.eta.agent.tool

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [isArchivedRead] 的边界测试。
 *
 * 这个函数决定「模型读到的是不是已结案的档案」，判错会直接影响它是否把历史结论当现状，
 * 所以把各种边界都钉死：空文件、没有归档区、恰好在边界上、整文件读取。
 */
class MemoryArchiveBoundaryTest {

    private fun doc(vararg lines: String) = lines.joinToString("\n")

    @Test
    fun `整文件读取不算归档读`() {
        val content = doc("# 核心记忆", "## 环境", "- x", "# 归档", "## 旧项目")
        assertFalse(isArchivedRead(content, startLine = null))
    }

    @Test
    fun `没有归档区时永不算归档`() {
        val content = doc("# 核心记忆", "## 环境", "- x", "## 规则", "- y")
        assertFalse(isArchivedRead(content, 1))
        assertFalse(isArchivedRead(content, 3))
        assertFalse(isArchivedRead(content, 5))
    }

    @Test
    fun `归档区之前不算归档`() {
        val content = doc("# 核心记忆", "## 环境", "- x", "# 归档", "## 旧项目")
        // "# 归档" 在第 4 行；1..3 都属于核心区
        assertFalse(isArchivedRead(content, 1))
        assertFalse(isArchivedRead(content, 3))
    }

    @Test
    fun `归档区标题行与被归档内容都算归档`() {
        val content = doc("# 核心记忆", "## 环境", "- x", "# 归档", "## 旧项目")
        assertTrue(isArchivedRead(content, 4))
        assertTrue(isArchivedRead(content, 5))
    }

    @Test
    fun `多次出现归档标题时以最后一次为界`() {
        // 正文里某章可能提到「# 归档」字样；分界线必须取最后一次。
        val content = doc(
            "# 核心记忆",
            "## 环境",
            "# 归档",
            "## 提到归档的正文",
            "# 归档",
            "## 真正的归档",
        )
        assertFalse("第 3 行的 # 归档 不是真分界", isArchivedRead(content, 3))
        assertTrue(isArchivedRead(content, 5))
        assertTrue(isArchivedRead(content, 6))
    }

    @Test
    fun `带首尾空白的归档标题也识别`() {
        val content = doc("# 核心记忆", "## 环境", "   # 归档   ", "## 旧项目")
        assertTrue(isArchivedRead(content, 3))
        assertTrue(isArchivedRead(content, 4))
    }

    @Test
    fun `空内容返回 false`() {
        assertFalse(isArchivedRead("", 1))
    }
}
