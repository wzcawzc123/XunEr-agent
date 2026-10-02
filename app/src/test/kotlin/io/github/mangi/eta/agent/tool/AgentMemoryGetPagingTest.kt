package io.github.mangi.eta.agent.tool

import android.content.Context
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.core.AgentLogger
import io.github.mangi.eta.data.repository.AgentMemoryMutation
import io.github.mangi.eta.data.repository.AgentMemoryRepository
import io.github.mangi.eta.data.repository.AgentMemoryWriteResult
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * `memory_get` 的分页续读指引。
 *
 * 背景：只回一个 `has_more=true` 而不告诉模型"下一段从哪读"，模型就会用同样的参数反复重试
 * —— 实测某会话连续 30 次相同参数的 `memory_get`，每次都只拿到第 1-63 行（同一页），
 * 模型的推理里写着"the returned window shows lines 1-63? Odd"。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentMemoryGetPagingTest {
    @Test
    fun pagedReadTellsWhereToContinue() {
        writeLongMemory()
        tools().use { tools ->
            val payload = JSONObject(tools.execute(call()).content)

            assertTrue("长记忆的首页应当还有后续内容", payload.getBoolean("has_more"))
            val endLine = payload.getInt("end_line")
            assertEquals(
                "续读起点应当是下一页的第一行",
                endLine + 1,
                payload.getInt("next_start_line"),
            )
            assertTrue(
                payload.getString("paging_hint").contains("start_line=${endLine + 1}"),
            )
        }
    }

    @Test
    fun followingTheHintWalksToTheEndAndThenStopsHinting() {
        writeLongMemory()
        tools().use { tools ->
            var startLine = 1
            var pages = 0
            while (true) {
                val payload = JSONObject(tools.execute(call(startLine = startLine)).content)
                pages++
                if (!payload.getBoolean("has_more")) {
                    // 末页不再提示续读，否则模型会以为还有内容而继续空转。
                    assertFalse(payload.has("next_start_line"))
                    assertFalse(payload.has("paging_hint"))
                    break
                }
                startLine = payload.getInt("next_start_line")
                check(pages < 50) { "按提示翻页未能走到末页" }
            }
            assertTrue("应当需要不止一页", pages > 1)
        }
    }

    @Test
    fun queryResultDoesNotCarryPagingHint() {
        writeLongMemory()
        tools().use { tools ->
            // 检索路径的 has_more 含义不同（"还有匹配未显示"），不能套用分页续读提示。
            val payload = JSONObject(tools.execute(call(query = "用于把文件撑到一页以上")).content)

            assertFalse(payload.has("next_start_line"))
            assertFalse(payload.has("paging_hint"))
        }
    }

    private fun writeLongMemory() {
        val snapshot = AgentMemoryRepository.snapshot()
        val body = (1..400).joinToString("\n") {
            "第 $it 行：这是一段用于把记忆文件撑到一页以上的填充内容"
        }
        val result = AgentMemoryRepository.mutate(
            AgentMemoryMutation.Append(revision = snapshot.revision, content = body),
        )
        check(result is AgentMemoryWriteResult.Success) { "准备测试数据失败：$result" }
    }

    private fun call(startLine: Int? = null, query: String? = null) = AgentModelClient.ToolCall(
        id = "memory-1",
        name = "memory_get",
        argumentsJson = JSONObject().apply {
            if (startLine != null) put("start_line", startLine)
            if (query != null) put("query", query)
        }.toString(),
    )

    private fun tools(): AgentLocalTools = AgentLocalTools(
        context = RuntimeEnvironment.getApplication() as Context,
        logger = NoOpLogger,
        terminalToolsEnabled = { true },
    )

    private object NoOpLogger : AgentLogger {
        override fun debug(message: () -> String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, throwable: Throwable?) = Unit
    }
}
