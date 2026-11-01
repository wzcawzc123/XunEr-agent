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
    fun anchorsAreInjectedVerbatim() {
        val instruction = AgentContextSummarizer.systemInstruction(
            roleplay = false,
            anchors = "## 用户约束\n不许删数据库\n## 关键标识\n包名 com.foo",
        )
        assertTrue(instruction.contains("不许删数据库"))
        assertTrue(instruction.contains("逐字原样保留"))
    }

    @Test
    fun thirdGenerationAddsDriftWarning() {
        val instruction = AgentContextSummarizer.systemInstruction(roleplay = false, generation = 3)
        assertTrue(instruction.contains("第 3 代压缩"))
        val first = AgentContextSummarizer.systemInstruction(roleplay = false, generation = 1)
        assertFalse(first.contains("代压缩"))
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
