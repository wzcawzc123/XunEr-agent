package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.AgentTokenUsage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentContextRecoveryTest {
    private val config = AgentModelClient.ModelConfig(
        baseUrl = "https://example.invalid", apiKey = "fixture", model = "fixture", systemPrompt = "固定约束",
        contextWindow = 900_000,
    )

    @Test
    fun missingOrInvalidWindowFailsBeforeAnyProviderRequest() {
        for (window in listOf(null, 0, -1)) {
            val failure = assertThrows(AgentModelFailure::class.java) {
                AgentModelClient.complete(config.copy(contextWindow = window), "继续",
                    AgentModelClient.ToolExecutor { error("不应执行工具") },
                    provider = provider { _, _ -> error("未设置窗口不能请求模型") })
            }
            assertEquals("CONTEXT_WINDOW_REQUIRED", failure.code)
            assertTrue(failure.message!!.contains("设置"))
            assertTrue(failure.message!!.contains("fixture"))
        }
    }

    @Test
    fun automaticCompactionUsesOnlyActualInputUsageAndConfiguredWindow() {
        // 阈值 = min(900_000 × 0.8, 900_000 − 16_384 − 65_536) = min(720_000, 818_080) = 720_000
        // （harness 口径：窗口比例与「窗口 − 输出预留 − 余量」取小）。
        for (input in listOf(null, 400_000, 719_999, 720_000)) {
            var summaries = 0
            val events = mutableListOf<AgentEvent>()
            val result = AgentModelClient.complete(config, "继续",
                AgentModelClient.ToolExecutor { error("不应执行工具") },
                history = history(), onEvent = events::add, provider = provider { request, emit ->
                    if (request.purpose == ProviderRequestPurpose.COMPACTION) {
                        summaries++
                        response("此前任务已完成。")
                    } else {
                        assertTrue(request.messages.toString().contains("长".repeat(100)))
                        emit(ProviderEvent.Usage(AgentTokenUsage(inputTokens = input, outputTokens = 800_000)))
                        response("完成")
                    }
                })
            assertEquals(if (input == 720_000) 1 else 0, summaries)
            assertEquals("完成", result.content)
            val completed = events.filterIsInstance<AgentEvent.ContextCompaction>().lastOrNull()
            if (input == 720_000) {
                assertEquals(720_000, completed!!.tokensBefore)
                assertNull(completed.tokensAfter)
            } else assertNull(completed)
        }
    }

    @Test
    fun disabledAutomaticCompactionStillAllowsManualCompaction() {
        val disabled = config.copy(autoCompactionEnabled = false)
        var summaries = 0
        val events = mutableListOf<AgentEvent>()
        val provider = provider { request, emit ->
            if (request.purpose == ProviderRequestPurpose.COMPACTION) {
                summaries++
                response("此前任务已完成。")
            } else {
                emit(ProviderEvent.Usage(AgentTokenUsage(inputTokens = 850_000)))
                response("完成")
            }
        }
        val answer = AgentModelClient.complete(disabled, "继续", AgentModelClient.ToolExecutor { error("不应执行工具") },
            history = history(), provider = provider, onEvent = events::add)
        assertEquals("完成", answer.content)
        assertEquals(0, summaries)
        assertTrue(events.none { it is AgentEvent.ContextCompaction })
        assertNull(answer.contextSnapshot)

        val manual = AgentModelClient.complete(disabled, "", AgentModelClient.ToolExecutor { error("不应执行工具") },
            history = history(), provider = provider, compactOnly = true)
        assertEquals(1, summaries)
        assertNotNull(manual.contextSnapshot)
        assertTrue(manual.transcript.isEmpty())
    }

    @Test
    fun compactionDoesNotRepeatUntilNewUsageArrives() {
        val messages = messages()
        var summaries = 0
        val session = AgentContextSession(config, messages, 1, "operation", provider { _, _ ->
            summaries++
            response("历史已完成。")
        }, AgentRunController(), { emptySet() }, {}, {})
        session.observeInputTokens(765_000)
        session.compact()
        session.compact()
        assertEquals(1, summaries)
        assertNotNull(session.snapshot())
    }

    @Test
    fun summaryFailureDoesNotSplitOrRetryAndKeepsOriginalHistory() {
        for (code in listOf("CONTEXT_OVERFLOW", "MODEL_TIMEOUT")) {
            val messages = messages()
            val original = messages.toString()
            var requests = 0
            val session = AgentContextSession(config, messages, 1, "operation", provider { _, _ ->
                requests++
                throw AgentModelFailure(code, true, "fixture")
            }, AgentRunController(), { emptySet() }, {}, { fail("失败摘要不能提交") })
            val failure = assertThrows(AgentModelFailure::class.java) { session.compact(force = true) }
            assertEquals(code, failure.code)
            assertEquals(1, requests)
            assertEquals(original, messages.toString())
            assertNull(session.snapshot())
        }
    }

    @Test
    fun invalidSummaryDoesNotRetryAndKeepsOriginalHistory() {
        for ((content, stop) in listOf("" to "stop", "半截" to "length", "长".repeat(16_001) to "stop")) {
            val messages = messages()
            val original = messages.toString()
            var requests = 0
            val session = AgentContextSession(config, messages, 1, "operation", provider { _, _ ->
                requests++
                response(content, stop)
            }, AgentRunController(), { emptySet() }, {}, { fail("不应提交无效摘要") })
            val failure = assertThrows(AgentModelFailure::class.java) { session.compact(force = true) }
            assertEquals("CONTEXT_SUMMARY_INVALID", failure.code)
            assertEquals(1, requests)
            assertEquals(original, messages.toString())
        }
    }

    @Test
    fun emptyLengthResponseIsNotReplayedEvenWhenInputIsNearWindow() {
        var requests = 0
        val failure = assertThrows(AgentModelExecutionException::class.java) {
            AgentModelClient.complete(config, "继续", AgentModelClient.ToolExecutor { error("不应执行工具") },
                history = history(), provider = provider { _, emit ->
                    requests++
                    emit(ProviderEvent.Usage(AgentTokenUsage(inputTokens = 850_000)))
                    response("", "length")
                })
        }
        assertEquals("MODEL_OUTPUT_LIMIT", (failure.cause as AgentModelFailure).code)
        assertEquals(1, requests)
        assertTrue(failure.transcript.isEmpty())
    }

    @Test
    fun failedChatAttemptUsageCannotTriggerCompactionAfterRetry() {
        var requests = 0
        val loop = AgentLoop(
            config, messages(), JSONArray(), provider { request, emit ->
                assertEquals(ProviderRequestPurpose.CHAT, request.purpose)
                if (++requests == 1) {
                    emit(ProviderEvent.Usage(AgentTokenUsage(inputTokens = 850_000)))
                    throw AgentModelFailure.incompleteStream("fixture")
                }
                response("完成")
            }, AgentModelClient.ToolExecutor { error("不应执行工具") }, AgentRunController(), AgentTraceFormatter(), {},
            modelRetry = AgentModelRetry { _, _ -> }, systemCount = 1,
        )
        assertEquals("完成", loop.run().content)
        assertEquals(2, requests)
        assertNull(loop.contextSnapshot())
    }

    @Test
    fun summaryCancellationKeepsOriginalContext() {
        val messages = messages()
        val original = messages.toString()
        val controller = AgentRunController()
        val session = AgentContextSession(config, messages, 1, "operation", provider { _, _ ->
            controller.cancel()
            response("不应提交")
        }, controller, { emptySet() }, {}, { fail("取消时不能提交") })
        assertThrows(Exception::class.java) { session.compact(force = true) }
        assertEquals(original, messages.toString())
        assertNull(session.snapshot())
    }

    @Test
    fun onlySummaryRequestsHaveAnOverallTimeout() {
        assertEquals(300_000, AgentHttpClient.modelClient(ProviderRequestPurpose.COMPACTION).callTimeoutMillis)
        assertEquals(0, AgentHttpClient.modelClient(ProviderRequestPurpose.CHAT).callTimeoutMillis)
    }

    // ---- M1.2 上下文失败可恢复 ----

    @Test
    fun summaryTriggerAlwaysPrecedesHardTrimSoSummaryGetsFirstChance() {
        // 档位关系锁：摘要必须先于硬裁剪触发（关系颠倒 = 历史被静默硬丢）。
        assertTrue(
            "TRIGGER_RATIO(${AgentContextSession.TRIGGER_RATIO}) 必须小于 TARGET_RATIO(${AgentHistoryTrimmer.TARGET_RATIO})",
            AgentContextSession.TRIGGER_RATIO < AgentHistoryTrimmer.TARGET_RATIO,
        )
        assertTrue(AgentHistoryTrimmer.TARGET_RATIO <= 1.0)
    }

    @Test
    fun compactionFailureDegradesInsteadOfKillingTheRun() {
        // M1.2 主路径：摘要压缩失败(如 CONTEXT_NO_REDUCTION)原本直接把整 run 抛死
        // ——真机 3,278,699 估算值案例；现在降级继续，任务照常完成。
        var compactionRequests = 0
        var chatRounds = 0
        val events = mutableListOf<AgentEvent>()
        val result = AgentModelClient.complete(config, "继续",
            AgentModelClient.ToolExecutor { AgentModelClient.ToolResult("""{"ok":true}""") },
            history = history(),
            provider = provider { request, emit ->
                if (request.purpose == ProviderRequestPurpose.COMPACTION) {
                    compactionRequests++
                    throw AgentModelFailure("CONTEXT_NO_REDUCTION", false, "摘要未能缩小上下文，原始上下文已保留。")
                }
                chatRounds++
                if (chatRounds == 1) {
                    emit(ProviderEvent.Usage(AgentTokenUsage(inputTokens = 800_000, outputTokens = 10)))
                    toolCallResponse("call-1")
                } else {
                    response("完成")
                }
            },
            onEvent = events::add,
        )

        assertEquals("降级后任务应照常完成", "完成", result.content)
        assertEquals("压缩应被尝试且仅一次", 1, compactionRequests)
        assertEquals("两轮正常请求都应发出", 2, chatRounds)
        val failed = events.filterIsInstance<AgentEvent.ContextCompaction>().last { it.phase == "failed" }
        assertEquals("失败原因必须可观测", "CONTEXT_NO_REDUCTION", failed.reasonCode)
    }

    @Test
    fun trimCannotFitRecoversWithContextExhaustedInsteadOfDoomedRequest() {
        // M1.2 终态：system 本身超过窗口 → 摘要失败后硬裁也救不回 →
        // 结构化可恢复错误 CONTEXT_EXHAUSTED（带下一步指引），而不是发出必然失败的请求。
        val bigConfig = config.copy(
            systemPrompt = "系".repeat(400_000),
            contextWindow = 50_000,
        )
        var chatRounds = 0
        val failure = assertThrows(AgentModelExecutionException::class.java) {
            AgentModelClient.complete(bigConfig, "继续",
                AgentModelClient.ToolExecutor { AgentModelClient.ToolResult("""{"ok":true}""") },
                history = history(),
                provider = provider { request, emit ->
                    if (request.purpose == ProviderRequestPurpose.COMPACTION) {
                        throw AgentModelFailure("CONTEXT_NO_REDUCTION", false, "fixture")
                    }
                    chatRounds++
                    if (chatRounds == 1) {
                        emit(ProviderEvent.Usage(AgentTokenUsage(inputTokens = 40_000, outputTokens = 10)))
                        toolCallResponse("call-1")
                    } else {
                        error("超窗的必然失败请求不应发出")
                    }
                },
            )
        }
        val cause = failure.cause as AgentModelFailure
        assertEquals("CONTEXT_EXHAUSTED", cause.code)
        assertTrue("错误必须给出可执行下一步", cause.message!!.contains("新开会话"))
        // C1 修复后行为提升：system 本身就超窗时 round1 即被硬裁复检拦下，
        // 不再先发一次注定失败的请求（修复前 chatRounds=1）。
        assertEquals("Provider 不应收到必然失败的请求", 0, chatRounds)
    }


    // ---- 审查探针（v3.4.0-audit）：新鲜 run 超窗时 round1 的兜底顺序 ----

    @Test
    fun freshRunWithOversizedHistoryTrimsRound1BeforeSending() {
        // 审查 C1（v3.4.0-audit）修复后行为锁：原门要求 usageObserved，导致新鲜 run 的
        // round1 巨型历史原样发出（探针曾实证 ≥700k chars 未裁剪）；修后 round1 即裁，
        // 请求必须显著缩小，HistoryTrimmed 在首轮出现，run 正常完成。
        // 窗口必须大于 Trimmer 的 TOOL_SCHEMA_RESERVE(24k)+system，否则 trim 必然装不下。
        val config = config.copy(contextWindow = 30_000)
        var chatRounds = 0
        var largestRequest = 0
        val events = mutableListOf<AgentEvent>()
        val result = AgentModelClient.complete(
            config, "继续",
            AgentModelClient.ToolExecutor { AgentModelClient.ToolResult("""{"ok":true}""") },
            history = history(),
            onEvent = events::add,
            provider = provider { request, emit ->
                if (request.purpose == ProviderRequestPurpose.COMPACTION) {
                    response("此前任务已完成。")
                } else {
                    chatRounds++
                    largestRequest = maxOf(largestRequest, request.messages.toString().length)
                    if (chatRounds == 1) {
                        emit(ProviderEvent.Usage(AgentTokenUsage(inputTokens = 480, outputTokens = 10)))
                        toolCallResponse("probe-1")
                    } else {
                        response("完成")
                    }
                }
            },
        )
        assertEquals("完成", result.content)
        assertTrue("round1/2 都应发出", chatRounds >= 2)
        assertTrue(
            "round1 请求必须已被硬裁(远小于原始 700k+ chars)，实测 largest=$largestRequest",
            largestRequest < 400_000,
        )
        val trims = events.filterIsInstance<AgentEvent.HistoryTrimmed>()
        assertTrue(
            "round1 即触发硬裁，实测 trims=${trims.size}",
            trims.isNotEmpty(),
        )
    }

    private fun toolCallResponse(id: String) = ProviderResponse(
        JSONObject()
            .put("role", "assistant")
            .put("content", "")
            .put("finish_reason", "tool_calls")
            .put(
                "tool_calls",
                JSONArray().put(
                    JSONObject()
                        .put("id", id)
                        .put("type", "function")
                        .put(
                            "function",
                            JSONObject().put("name", "get_current_context").put("arguments", "{}"),
                        ),
                ),
            ),
    )

    private fun history() = listOf(
        AgentModelClient.ConversationMessage("user", "旧任务"),
        AgentModelClient.ConversationMessage("assistant", "长".repeat(780_000)),
    )

    private fun messages() = JSONArray().put(JSONObject().put("role", "system").put("content", "固定约束")).also { source ->
        history().forEach { source.put(AgentConversationCodec.toJsonObject(it)) }
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
