package io.github.mangi.eta.agent.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 摘要小节结构校验（纯 JVM，无 Android 依赖）。
 *
 * 真机取证（2026-10-09，会话「内存管理模块-新会话」）：模型漏掉 `## 关键上下文` 整节，
 * 该节承载的用户偏好 / 约束 / 未决问题随之全部消失（实测关键词命中均为 0 次）。
 * 这组用例锁住"缺节必须被检出"这条契约，供 summarize 决定是否重试一次。
 */
class AgentContextSummarizerSectionTest {

    private val allSections = """
        ## 原始需求与意图
        - 目标
        ## 关键技术概念
        - 技术
        ## 文件与代码
        - 路径
        ## 错误与修复
        - 修复
        ## 待办事项
        - 待办
        ## 当前工作
        - 进行中
        ## 下一步
        - 下一步
        ## 关键上下文
        - 用户偏好
    """.trimIndent()

    @Test
    fun `齐全的小节无缺失`() {
        assertEquals(emptyList<String>(), AgentContextSummarizer.missingSections(allSections))
    }

    @Test
    fun `缺少关键上下文时被检出`() {
        // 复现真机样本：模型输出止于「下一步」，最后一节整节蒸发。
        val truncated = allSections.substringBefore("## 关键上下文")
        assertEquals(listOf("## 关键上下文"), AgentContextSummarizer.missingSections(truncated))
    }

    @Test
    fun `多节缺失按声明顺序返回`() {
        val sparse = "## 原始需求与意图\n- a\n## 下一步\n- b"
        val missing = AgentContextSummarizer.missingSections(sparse)
        assertEquals(6, missing.size)
        assertTrue("顺序应保持声明顺序", missing.first() == "## 关键技术概念")
        assertTrue("应包含末节", missing.last() == "## 关键上下文")
    }

    @Test
    fun `空内容视为全部缺失`() {
        assertEquals(8, AgentContextSummarizer.missingSections("").size)
    }
}
