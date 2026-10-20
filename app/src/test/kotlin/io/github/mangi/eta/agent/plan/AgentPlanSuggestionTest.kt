package io.github.mangi.eta.agent.plan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPlanSuggestionTest {

    private val marker = AgentPlanSuggestion.MARKER

    @Test
    fun `最后一行带标记时拆出正文与理由`() {
        val parsed = AgentPlanSuggestion.parse(
            "这个任务要改 3 个文件还要动系统设置。\n$marker 先调研再改，避免改错",
        )!!
        assertEquals("这个任务要改 3 个文件还要动系统设置。", parsed.body)
        assertEquals("先调研再改，避免改错", parsed.reason)
    }

    @Test
    fun `只带标记没有理由时 reason 为 null`() {
        val parsed = AgentPlanSuggestion.parse("建议先出方案。\n$marker")!!
        assertEquals("建议先出方案。", parsed.body)
        assertNull(parsed.reason)
    }

    @Test
    fun `标记后允许空行`() {
        val parsed = AgentPlanSuggestion.parse("正文\n$marker\n\n")!!
        assertEquals("正文", parsed.body)
    }

    @Test
    fun `标记不在最后一行时不算建议`() {
        // 模型在正文中间提到这个词（比如解释协议）不应触发按钮。
        assertNull(AgentPlanSuggestion.parse("正文\n$marker\n后面还有内容"))
    }

    @Test
    fun `没有标记返回 null`() {
        assertNull(AgentPlanSuggestion.parse(null))
        assertNull(AgentPlanSuggestion.parse(""))
        assertNull(AgentPlanSuggestion.parse("普通回复，没有任何标记"))
    }

    @Test
    fun `标记行前后空白不影响识别`() {
        val parsed = AgentPlanSuggestion.parse("正文\n   $marker   理由   ")!!
        assertEquals("正文", parsed.body)
        assertEquals("理由", parsed.reason)
    }

    @Test
    fun `系统提示里必须包含标记与不准自行进入的约束`() {
        val instruction = AgentPlanSuggestion.promptInstruction()
        assertTrue("必须告诉模型标记长什么样", instruction.contains(marker))
        assertTrue("必须禁止模型替用户决定", instruction.contains("不要替用户决定"))
        assertTrue("必须避免对简单任务频繁建议", instruction.contains("简单任务直接做"))
    }
}
