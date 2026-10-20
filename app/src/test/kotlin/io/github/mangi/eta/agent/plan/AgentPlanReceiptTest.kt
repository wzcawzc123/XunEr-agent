package io.github.mangi.eta.agent.plan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPlanReceiptTest {

    @Test
    fun `拼装与解析互为逆运算`() {
        val header = "计划 v1 已提交，共 3 步，等待用户批准。"
        val markdown = "# 目标\n\n- 读取权限\n- 打开设置"
        val raw = AgentPlanReceipt.build(header, markdown)

        val parsed = AgentPlanReceipt.parse(raw)!!
        assertEquals(header, parsed.header)
        assertEquals(markdown, parsed.markdown)
    }

    @Test
    fun `正文里的水平分隔线不会把计划劈开`() {
        // 用固定标记而不是 --- 分隔线，正是为了这个场景。
        val markdown = "# 步骤\n\n---\n\n1. 第一步\n\n---\n\n2. 第二步"
        val parsed = AgentPlanReceipt.parse(AgentPlanReceipt.build("头", markdown))!!
        assertEquals(markdown, parsed.markdown)
        assertTrue("分隔线必须原样保留", parsed.markdown.contains("---"))
    }

    @Test
    fun `没有标记返回 null`() {
        assertNull(AgentPlanReceipt.parse(null))
        assertNull(AgentPlanReceipt.parse(""))
        assertNull(AgentPlanReceipt.parse("计划已提交，但没有标记"))
    }

    @Test
    fun `标记不成对时返回 null`() {
        assertNull(AgentPlanReceipt.parse("头\n${AgentPlanReceipt.BEGIN}\n# 正文"))
        assertNull(AgentPlanReceipt.parse("头\n${AgentPlanReceipt.END}\n# 正文"))
    }

    @Test
    fun `正文为空仍解析成功`() {
        // 「有计划但正文为空」与「这不是计划回执」是两回事，前者仍要走 Markdown 分支。
        val raw = "${AgentPlanReceipt.BEGIN}\n\n${AgentPlanReceipt.END}"
        val parsed = AgentPlanReceipt.parse(raw)!!
        assertEquals("", parsed.markdown)
    }

    @Test
    fun `标记出现多次时取第一对`() {
        val raw = "头A\n${AgentPlanReceipt.BEGIN}\n正文1\n${AgentPlanReceipt.END}\n" +
            "头B\n${AgentPlanReceipt.BEGIN}\n正文2\n${AgentPlanReceipt.END}"
        val parsed = AgentPlanReceipt.parse(raw)!!
        assertEquals("正文1", parsed.markdown)
    }
}
