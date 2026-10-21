package io.github.mangi.eta.agent.memory

import io.github.mangi.eta.data.repository.AgentMemorySnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentMemoryContextBuilderTest {
    @Test
    fun coreBudgetTracksWindowWithSafeUnknownFallback() {
        assertEquals(8_000, AgentMemoryContextBuilder.coreBudgetChars(null))
        assertEquals(8_000, AgentMemoryContextBuilder.coreBudgetChars(128_000))
        assertEquals(16_000, AgentMemoryContextBuilder.coreBudgetChars(256_000))
        assertEquals(32_000, AgentMemoryContextBuilder.coreBudgetChars(1_000_000))
        assertEquals(4_000, AgentMemoryContextBuilder.coreBudgetChars(16_000))
    }

    @Test
    fun injectsOnlyCoreSectionAndBoundedHeadingIndex() {
        val content = "# 核心记忆\n长期偏好\n## 关系\n家人\n# 详细背景\n不应自动注入"
        val context = AgentMemoryContextBuilder.build(snapshot(content), 128_000)

        assertEquals("# 核心记忆\n长期偏好\n## 关系\n家人", context.coreContent)
        assertFalse(context.coreContent.contains("不应自动注入"))
        assertEquals("# 核心记忆\n## 关系\n# 详细背景", context.headingIndex)
        assertFalse(context.coreTruncated)
    }

    @Test
    fun oversizedCoreIsTruncatedOnLineBoundaryAndReportsContinuation() {
        val content = "# 核心记忆\n" + "a".repeat(10_000)
        val snapshot = snapshot(content)
        val context = AgentMemoryContextBuilder.build(snapshot, null)

        // 按行边界截断：只剩标题行能被完整保住，超预算的那一行整行不注入
        // （而不是像原来那样 take(8000) 把最后一行切在句子中间还不说明从哪继续）。
        assertEquals("# 核心记忆", context.coreContent)
        assertTrue(context.coreTruncated)
        assertEquals(1, context.coreInjectedLines)
        assertEquals(2, context.coreTotalLines)
        assertEquals(2, context.coreNextLine)
        assertEquals(snapshot.revision, context.revision)
    }

    @Test
    fun everyCoreHeadingSectionIsMergedIntoTheInjectedCore() {
        val content = "# 核心记忆\n第一段\n# 项目\n只应按需读取\n# 核心记忆\n第二段"
        val context = AgentMemoryContextBuilder.build(snapshot(content), 256_000)

        // 两个 "# 核心记忆" 段都会被注入。原先只取第一段，第二段整段被静默排除在核心记忆之外。
        assertEquals(2, context.coreSectionCount)
        assertTrue(context.coreContent.contains("第一段"))
        assertTrue(context.coreContent.contains("第二段"))
        assertFalse(context.coreContent.contains("只应按需读取"))
        assertTrue(context.headingWarning.contains("同名标题出现多次"))
    }

    @Test
    fun singleCoreSectionProducesNoHeadingWarning() {
        val context = AgentMemoryContextBuilder.build(
            snapshot("# 核心记忆\n偏好\n## 项目\nEta Agent"),
            128_000,
        )

        assertEquals(1, context.coreSectionCount)
        assertEquals("", context.headingWarning)
        assertFalse(context.coreTruncated)
        assertEquals(null, context.coreNextLine)
    }

    @Test
    fun detailsWithoutCoreHeadingAreIndexedButNotAutomaticallyInjected() {
        val context = AgentMemoryContextBuilder.build(
            snapshot("# 项目\n只应按需读取的细节"),
            256_000,
        )

        assertEquals("", context.coreContent)
        assertEquals("# 项目", context.headingIndex)
    }

    private fun snapshot(content: String): AgentMemorySnapshot = AgentMemorySnapshot(
        content = content,
        revision = "a".repeat(64),
        byteSize = content.toByteArray(Charsets.UTF_8).size,
        lineCount = content.lines().size,
    )
}
