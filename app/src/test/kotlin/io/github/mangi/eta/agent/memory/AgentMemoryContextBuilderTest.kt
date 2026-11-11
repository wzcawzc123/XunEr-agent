package io.github.mangi.eta.agent.memory

import io.github.mangi.eta.data.repository.AgentMemorySnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
    fun oversizedCoreIsTruncatedWithoutDroppingRevision() {
        val content = "# 核心记忆\n" + "a".repeat(10_000)
        val snapshot = snapshot(content)
        val context = AgentMemoryContextBuilder.build(snapshot, null)

        assertEquals(8_000, context.coreContent.length)
        assertTrue(context.coreTruncated)
        assertEquals(snapshot.revision, context.revision)
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

    @Test
    fun gaugeReportsExactlyTheInjectionSideNumbers() {
        val snapshot = snapshot("# 核心记忆\n长期偏好\n## 关系\n家人")
        val context = AgentMemoryContextBuilder.build(snapshot, 32_000)

        val report = AgentMemoryGauge.report(snapshot, 32_000)

        // 工具层与注入侧必须同口径：分母不一致时，模型会误以为核心区快满了并去砍内容。
        assertEquals(context.coreContent.length, report.getInt("used_chars"))
        assertEquals(context.coreBudgetChars, report.getInt("budget_chars"))
        assertEquals(context.coreTruncated, report.getBoolean("truncated"))
        assertEquals(32_000, report.getInt("window"))
    }

    @Test
    fun gaugeFallsBackToSameDefaultWindowAsInjection() {
        val snapshot = snapshot("# 核心记忆\n" + "a".repeat(10_000))
        val context = AgentMemoryContextBuilder.build(snapshot, null)

        val report = AgentMemoryGauge.report(snapshot, null)

        assertEquals(context.coreBudgetChars, report.getInt("budget_chars"))
        assertTrue(report.getBoolean("truncated"))
        assertEquals(AgentMemoryContextBuilder.DEFAULT_CONTEXT_WINDOW, report.getInt("window"))
    }

    @Test
    fun mergesEveryCoreSectionIncludingLegacyDuplicates() {
        // 旧版本允许 append 出重复标题，第二段曾被整段静默丢弃 —— 现在必须全部注入。
        val content = "# 核心记忆\n第一段\n# 其他\n不注入\n# 核心记忆\n第二段\n## 子节\n也注入"
        val context = AgentMemoryContextBuilder.build(snapshot(content), 128_000)

        assertEquals(2, context.coreSectionCount)
        assertTrue(context.coreContent.contains("第一段"))
        assertTrue(context.coreContent.contains("第二段"))
        assertTrue(context.coreContent.contains("也注入"))
        assertFalse(context.coreContent.contains("不注入"))
        assertTrue(context.headingWarning.contains("2 个同名"))
    }

    @Test
    fun truncationKeepsWholeLinesAndReportsResumeAnchor() {
        val body = (1..200).joinToString("\n") { "第${it}行" + "x".repeat(95) }
        val context = AgentMemoryContextBuilder.build(snapshot("# 核心记忆\n$body"), 128_000)

        assertTrue(context.coreTruncated)
        // 注入的行数要与实际返回的行数一致，否则"已注入 N/M 行"会骗人。
        assertEquals(context.coreInjectedLines, context.coreContent.lines().size)
        assertEquals(201, context.coreTotalLines)
        // 除最后一行外都必须是完整的（最后一行可能被预算切开）。
        context.coreContent.lines().drop(1).dropLast(1).forEach { line ->
            assertTrue("中间行被腰斩：${line.take(12)}…", line.length >= 98)
        }
        assertNotNull(context.coreNextLine)
        assertTrue(context.coreNextLine!! > 1)
    }

    @Test
    fun truncationNeverSplitsSurrogatePairs() {
        // 预算边界落在 emoji 中间：必须退回一个字符，而不是留下孤立的高位代理。
        val context = AgentMemoryContextBuilder.build(
            snapshot("# 核心记忆\n" + "😀".repeat(5_000)),
            null,
        )

        assertTrue(context.coreTruncated)
        // 末尾不能是孤立的高位代理（"😀" 被切成一半就会留下它）。
        assertFalse(Character.isHighSurrogate(context.coreContent.last()))
        // 整串里不能出现任何孤立代理。
        val text = context.coreContent
        for (index in text.indices) {
            if (Character.isHighSurrogate(text[index])) {
                assertTrue("第 $index 位出现孤立高位代理", index + 1 < text.length)
                assertTrue("第 $index 位的代理对不完整", Character.isLowSurrogate(text[index + 1]))
            }
        }
    }

    @Test
    fun headingIndexReportsWhenItIsTruncated() {
        val content = (1..800).joinToString("\n") { "## 标题$it" }
        val context = AgentMemoryContextBuilder.build(snapshot(content), 128_000)

        assertTrue(context.headingIndex.length <= 4_000)
        assertTrue(context.headingWarning.contains("仅列出前"))
        assertTrue(context.headingWarning.contains("800"))
    }

    private fun snapshot(content: String): AgentMemorySnapshot = AgentMemorySnapshot(
        content = content,
        revision = "a".repeat(64),
        byteSize = content.toByteArray(Charsets.UTF_8).size,
        lineCount = content.lines().size,
    )
}
