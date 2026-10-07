package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.AgentTokenUsage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * 上下文成本护栏（spec：`docs/specs/context-cost-guard.md`）。
 *
 * 起因：真机会话 conv-3d2f1ce4 在 75 万触发线下单轮 input 达 749,916 tokens，
 * 压缩失败后每轮重试一次全量摘要请求，额度瞬间耗尽（HTTP 402）。
 */
class AgentContextCostGuardTest {
    private val config = AgentModelClient.ModelConfig(
        baseUrl = "https://example.invalid", apiKey = "fixture", model = "fixture",
        systemPrompt = "固定约束", contextWindow = 128_000,
    )

    @Test
    fun triggerLineIsCappedByAbsoluteLimitForLargeWindows() {
        assertEquals(200_000, AgentContextBudget.triggerTokens(1_000_000))
        assertEquals(200_000, AgentContextBudget.triggerTokens(900_000))
        assertEquals(192_000, AgentContextBudget.triggerTokens(256_000))
        assertEquals(96_000, AgentContextBudget.triggerTokens(128_000))
        assertNull(AgentContextBudget.triggerTokens(null))
        assertNull(AgentContextBudget.triggerTokens(0))
        assertFalse(AgentContextBudget(1_000_000).shouldCompact(199_999))
        assertTrue(AgentContextBudget(1_000_000).shouldCompact(200_000))
    }

    @Test
    fun failedCompactionBlocksFurtherAutomaticAttemptsInSameRun() {
        val messages = longHistory()
        val original = messages.toString()
        var requests = 0
        val session = AgentContextSession(config, messages, 1, "operation", provider { _, _ ->
            requests++
            throw AgentModelFailure("CONTEXT_SUMMARY_FAILED", true, "fixture")
        }, AgentRunController(), { emptySet() }, {}, { fail("失败摘要不能提交快照") })
        session.observeInputTokens(200_000)
        assertThrows(AgentModelFailure::class.java) { session.compact() }
        session.compact()
        session.compact()
        assertEquals("失败一次后本 run 内不得再发起摘要请求", 1, requests)
        assertEquals(original, messages.toString())
        assertNull(session.snapshot())
    }

    @Test
    fun forcedCompactionStillRunsAfterAutomaticFailure() {
        val messages = longHistory()
        var requests = 0
        val session = AgentContextSession(config, messages, 1, "operation", provider { _, _ ->
            requests++
            throw AgentModelFailure("CONTEXT_SUMMARY_FAILED", true, "fixture")
        }, AgentRunController(), { emptySet() }, {}, { fail("失败摘要不能提交快照") })
        session.observeInputTokens(200_000)
        assertThrows(AgentModelFailure::class.java) { session.compact() }
        assertThrows(AgentModelFailure::class.java) { session.compact(force = true) }
        assertEquals("force 不受熔断限制（用户显式要求）", 2, requests)
    }

    @Test
    fun compactionWithoutEnoughReductionIsRejected() {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", "规则".repeat(60_000)))
            .put(AgentConversationCodec.userTextMessage("旧任务"))
            .put(AgentConversationCodec.toJsonObject(AgentModelClient.ConversationMessage("assistant", "已完成")))
            .put(AgentConversationCodec.userTextMessage("最新任务"))
        val before = messages.toString()
        val failure = assertThrows(AgentModelFailure::class.java) {
            AgentContextCompactor(config, provider { _, _ -> response("已完成此前任务。") }, AgentRunController())
                .compact(messages, 1, emptySet())
        }
        assertEquals("CONTEXT_NO_REDUCTION", failure.code)
        assertEquals("无效压缩不得改写上下文", before, messages.toString())
    }

    /**
     * R6：摘要熔断后，硬裁剪线必须从「窗口」降到「触发线」。
     *
     * 窗口 900_000 → 触发线 200_000；历史约 78 万（未超窗口）。首轮摘要失败后，
     * 若仍按窗口兜底，第二轮会整份重发约 78 万 tokens；修复后应被裁到触发线量级。
     */
    @Test
    fun failedCompactionFallsBackToTrimAtTriggerLine() {
        val windowConfig = config.copy(contextWindow = 900_000)
        val events = mutableListOf<AgentEvent>()
        val estimates = mutableListOf<Int>()
        var chatRounds = 0
        AgentModelClient.complete(
            windowConfig, "继续",
            AgentModelClient.ToolExecutor { AgentModelClient.ToolResult("""{"ok":true}""") },
            history = trimHistory(),
            onEvent = events::add,
            provider = provider { request, emit ->
                if (request.purpose == ProviderRequestPurpose.COMPACTION) {
                    throw AgentModelFailure("CONTEXT_NO_REDUCTION", false, "fixture")
                }
                chatRounds++
                estimates += AgentContextBudget.rawEstimate(request.messages)
                if (chatRounds == 1) {
                    emit(ProviderEvent.Usage(AgentTokenUsage(inputTokens = 800_000, outputTokens = 10)))
                    toolCallResponse("call-1")
                } else {
                    response("完成")
                }
            },
        )
        assertTrue("应完成两轮对话", chatRounds >= 2)
        assertTrue(
            "摘要熔断后第二轮必须裁到触发线量级，实测 ${estimates.getOrNull(1)}",
            (estimates.getOrNull(1) ?: Int.MAX_VALUE) < 300_000,
        )
        assertTrue(
            "硬裁剪必须可观测（HistoryTrimmed）",
            events.filterIsInstance<AgentEvent.HistoryTrimmed>().isNotEmpty(),
        )
    }

    private fun trimHistory() = listOf(
        AgentModelClient.ConversationMessage("user", "旧任务"),
        AgentModelClient.ConversationMessage("assistant", "长".repeat(780_000)),
    )

    private fun longHistory(): JSONArray =
        JSONArray().put(JSONObject().put("role", "system").put("content", "固定约束")).also { array ->
            (1..6).forEach { turn ->
                array.put(AgentConversationCodec.userTextMessage("问题 $turn"))
                array.put(AgentConversationCodec.toJsonObject(
                    AgentModelClient.ConversationMessage("assistant", "事实 $turn ".repeat(500)),
                ))
            }
        }

    private fun response(text: String, stop: String = "stop") = ProviderResponse(
        JSONObject().put("role", "assistant").put("content", text).put("finish_reason", stop),
    )

    private fun toolCallResponse(id: String) = ProviderResponse(
        JSONObject()
            .put("role", "assistant")
            .put("content", "")
            .put("finish_reason", "tool_calls")
            .put("tool_calls", JSONArray().put(
                JSONObject().put("id", id).put("type", "function")
                    .put("function", JSONObject().put("name", "get_current_context").put("arguments", "{}")),
            )),
    )

    private fun provider(block: (ProviderRequest, (ProviderEvent) -> Unit) -> ProviderResponse) = object : AgentProviderClient {
        override val id = "fixture"
        override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, true, true, false, false)
        override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit): ProviderResponse =
            block(request, onEvent)
    }
}
