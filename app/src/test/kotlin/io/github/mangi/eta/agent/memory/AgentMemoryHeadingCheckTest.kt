package io.github.mangi.eta.agent.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AgentMemoryHeadingCheck] 的测试。
 *
 * 真实场景：MEMORY.md 里 `## 待办` 出现过 3 次 ——
 * 这会让 `memory_get(section="待办")` 命中哪一段变得不可预测。
 */
class AgentMemoryHeadingCheckTest {

    private fun doc(vararg lines: String) = lines.joinToString("\n")

    @Test
    fun `新标题不报重复`() {
        val existing = doc("# 核心记忆", "## 环境", "- x")
        val incoming = doc("## 新章节", "- y")
        val r = AgentMemoryHeadingCheck.check(existing, incoming)
        assertFalse(r.hasDuplicates)
        assertEquals(listOf("新章节"), r.incoming)
    }

    @Test
    fun `同名二级标题被识别`() {
        val existing = doc("# 核心记忆", "## 待办", "- 旧任务")
        val incoming = doc("## 待办", "- 新任务")
        val r = AgentMemoryHeadingCheck.check(existing, incoming)
        assertTrue(r.hasDuplicates)
        assertEquals(listOf("待办"), r.duplicates)
    }

    @Test
    fun `真实场景 待办出现三次`() {
        val existing = doc(
            "# 核心记忆",
            "## 待办", "- a",
            "## 环境", "- b",
            "## 待办", "- c",
            "## 待办", "- d",
        )
        val incoming = doc("## 待办", "- e")
        val r = AgentMemoryHeadingCheck.check(existing, incoming)
        assertEquals("重复项应去重", listOf("待办"), r.duplicates)
    }

    @Test
    fun `一到三级标题都能识别`() {
        val existing = doc("# 一级", "## 二级", "### 三级")
        val incoming = doc("# 一级", "## 二级", "### 三级")
        val r = AgentMemoryHeadingCheck.check(existing, incoming)
        assertEquals(3, r.duplicates.size)
    }

    @Test
    fun `四级以下不算标题`() {
        val existing = doc("#### 四级")
        val incoming = doc("#### 四级")
        val r = AgentMemoryHeadingCheck.check(existing, incoming)
        assertFalse("索引口径只到三级，四级不应互相报重", r.hasDuplicates)
    }

    @Test
    fun `无标题的内容不产生结果`() {
        val r = AgentMemoryHeadingCheck.check(doc("## 环境"), "- 纯文本，没有标题")
        assertFalse(r.hasDuplicates)
        assertTrue(r.incoming.isEmpty())
    }

    @Test
    fun `标题前后空白被规范化`() {
        val existing = doc("##   环境   ")
        val incoming = doc("## 环境")
        val r = AgentMemoryHeadingCheck.check(existing, incoming)
        assertEquals(listOf("环境"), r.duplicates)
    }

    @Test
    fun `同一内容里重复的标题只报一次`() {
        val existing = doc("## 状态")
        val incoming = doc("## 状态", "## 状态")
        val r = AgentMemoryHeadingCheck.check(existing, incoming)
        assertEquals(listOf("状态"), r.duplicates)
    }
}
