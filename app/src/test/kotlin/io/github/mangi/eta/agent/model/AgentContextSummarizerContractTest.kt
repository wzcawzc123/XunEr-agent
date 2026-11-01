package io.github.mangi.eta.agent.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L2 交接卡契约测试：模板必须含全部段标题、约束段必须要求「原文引用」；
 * 校验函数必须拒绝缺段/空白的摘要。
 */
class AgentContextSummarizerContractTest {

    @Test
    fun instructionContainsAllRequiredHeaders() {
        val instruction = AgentContextSummarizer.systemInstruction(roleplay = false)
        AgentContextSummarizer.REQUIRED_HEADERS.forEach { header ->
            assertTrue("缺段 $header", instruction.contains(header))
        }
        assertTrue("约束段必须要求原文引用", instruction.contains("原文"))
    }

    @Test
    fun roleplayAddsPlotSection() {
        val instruction = AgentContextSummarizer.systemInstruction(roleplay = true)
        assertTrue(instruction.contains("## 剧情"))
    }

    @Test
    fun validationRejectsBlankAndNull() {
        assertFalse(AgentContextSummarizer.isValidSummary(""))
        assertFalse(AgentContextSummarizer.isValidSummary("null"))
        assertFalse(AgentContextSummarizer.isValidSummary("   "))
    }

    @Test
    fun validationRejectsMissingSections() {
        val partial = "## 目标\n做点事\n## 已完成\n做了些事\n"
        assertFalse(AgentContextSummarizer.isValidSummary(partial))
    }

    @Test
    fun validationAcceptsCompleteCard() {
        val full = AgentContextSummarizer.REQUIRED_HEADERS.joinToString("\n内容\n") { it }
        assertTrue(AgentContextSummarizer.isValidSummary(full))
    }
}
