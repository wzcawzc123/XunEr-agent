package io.github.mangi.eta.agent.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** U5 大纲直达的纯函数：headings 收 1-3 级、section 按标题取整节（到下一个同级/更高级标题）。 */
class AgentMemoryPreviewOutlineTest {
    private val content = """
        # 核心记忆
        偏好内容
        ### 细节小节
        三级标题也进大纲
        ## 关系
        家人
        # 归档
        已收官项目
        ## 归档子项
        更多
    """.trimIndent()

    @Test
    fun headingsCollectsLevel1To3AndDeduplicates() {
        val headings = AgentMemoryPreview.headings(content)
        assertEquals(
            listOf("# 核心记忆", "### 细节小节", "## 关系", "# 归档", "## 归档子项"),
            headings,
        )
    }

    @Test
    fun sectionStopsAtNextSameOrHigherLevel() {
        val relation = AgentMemoryPreview.section(content, "## 关系")
        assertEquals("## 关系\n家人", relation)
        // 归档子项是二级，到文件尾（没有更高级或同级标题）。
        val archived = AgentMemoryPreview.section(content, "## 归档子项")
        assertEquals("## 归档子项\n更多", archived)
    }

    @Test
    fun sectionMatchingIgnoresCaseAndHashCount() {
        val match = AgentMemoryPreview.section(content, "关系")
        assertTrue(match!!.startsWith("## 关系"))
        val caseInsensitive = AgentMemoryPreview.section(content, "# 关系")
        assertEquals(match, caseInsensitive)
    }

    @Test
    fun sectionReturnsNullForUnknownHeading() {
        assertNull(AgentMemoryPreview.section(content, "不存在的标题"))
        assertNull(AgentMemoryPreview.section(content, "   "))
    }

    @Test
    fun sectionCoversArchivedContentThatPreviewSliceHides() {
        // 归档区在预览切片里看不到，但大纲必须能直达（U5 的核心价值）。
        val archived = AgentMemoryPreview.section(content, "# 归档")
        assertTrue(archived!!.contains("已收官项目"))
        assertTrue(archived.contains("## 归档子项"))
    }
}
