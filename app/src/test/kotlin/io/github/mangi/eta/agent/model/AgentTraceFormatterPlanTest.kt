package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.plan.AgentPlanUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具结果摘要 × 计划模式的回归测试。
 *
 * **锁死一个真机 bug**：`todo_write` 返回的是纯文本清单（不是 JSON），
 * 而 `AgentTraceFormatter.summarizeResult` 的兜底分支是
 * `json?.let{...} ?: "完成"` —— JSON 解析失败就直接变成"完成"两个字，
 * 计划数据全丢。UI 的 `latestPlanUi` 再从这条消息里解析清单，
 * 自然解析不出来 → **进度条永远不显示**。
 *
 * 现象：`conversation_todos` 表有数据、状态也对，但界面上什么都没有。
 */
class AgentTraceFormatterPlanTest {

    private val formatter = AgentTraceFormatter()

    private val receipt = buildString {
        appendLine("任务清单已更新：1/3 完成。当前进行中：验证状态更新与界面刷新。")
        appendLine()
        appendLine("1. [已完成] 验证 plan 持久化写入")
        appendLine("2. [进行中] 验证状态更新与界面刷新")
        appendLine("3. [待开始] 验证跨轮次/重启后保持")
    }

    @Test
    fun `todo_write 的结果不能被压成完成`() {
        val summary = formatter.summarizeResult(
            toolName = "todo_write",
            result = AgentModelClient.ToolResult(receipt),
        )
        assertFalse(
            "结果被兜底成「完成」就说明计划数据丢了（真实 bug）",
            summary.trim() == "完成",
        )
        assertTrue("必须保留回执原文", summary.contains("任务清单已更新"))
    }

    @Test
    fun `摘要能被 UI 解析出完整的计划`() {
        val summary = formatter.summarizeResult(
            toolName = "todo_write",
            result = AgentModelClient.ToolResult(receipt),
        )
        val ui = AgentPlanUi.parseFrom(summary)
        assertNotNull("UI 解析不出来 → 进度条不显示", ui)
        assertEquals(1, ui!!.done)
        assertEquals(3, ui.total)
        assertEquals(3, ui.items.size)
        assertEquals("验证状态更新与界面刷新", ui.running)
    }

    @Test
    fun `其他工具仍走各自的摘要逻辑`() {
        // 回归保护：加 todo_write 分支不应影响别的工具
        val terminal = formatter.summarizeResult(
            toolName = "terminal",
            result = AgentModelClient.ToolResult("""{"ok":true,"output":"hi"}"""),
        )
        assertFalse("terminal 不该返回清单原文", terminal.contains("任务清单已更新"))
    }
}
