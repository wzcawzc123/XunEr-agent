package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunController
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

    private fun provider(block: (ProviderRequest, (ProviderEvent) -> Unit) -> ProviderResponse) = object : AgentProviderClient {
        override val id = "fixture"
        override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, true, true, false, false)
        override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit): ProviderResponse =
            block(request, onEvent)
    }
}
