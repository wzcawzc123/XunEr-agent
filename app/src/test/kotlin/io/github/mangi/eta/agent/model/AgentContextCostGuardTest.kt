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
 * 阈值公式对齐 deepseek-ai/deepseek-harness `compaction-basic`（MIT）：
 * `min(W × thresholdRatio, W − 输出预留 − 余量)`，保留比例同为 0.16。
 */
class AgentContextCostGuardTest {
    private val config = AgentModelClient.ModelConfig(
        baseUrl = "https://example.invalid", apiKey = "fixture", model = "fixture",
        systemPrompt = "固定约束", contextWindow = 128_000,
    )

    @Test
    fun triggerAndRetainBudgetsFollowHarnessFormula() {
        // W = 1,048,576：reserve = min(16384, W/16) = 16384；headroom = min(65536, W/8) = 65536
        // pressureBudget = W − 16384 − 65536 = 966,656；byRatio = W × 0.8 = 838,860 → 取小 = 838,860
        assertEquals(838_860, AgentContextBudget.triggerTokens(1_048_576))
        assertEquals(165_150, AgentContextBudget.retainTokens(1_048_576))
        assertEquals(800_000, AgentContextBudget.triggerTokens(1_000_000))
        assertEquals(204_800, AgentContextBudget.triggerTokens(256_000))
        assertEquals(102_400, AgentContextBudget.triggerTokens(128_000))
        assertEquals(64_000, AgentContextBudget.triggerTokens(80_000))
        assertNull(AgentContextBudget.triggerTokens(null))
        assertNull(AgentContextBudget.triggerTokens(0))
        assertFalse(AgentContextBudget(1_048_576).shouldCompact(838_859))
        assertTrue(AgentContextBudget(1_048_576).shouldCompact(838_860))
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
     * 80% 档（W = 900_000 → 触发线 720_000）下，摘要熔断后硬裁剪线必须降到触发线，
     * 否则每轮仍会整份发出超线上下文。
     */
    @Test
    fun failedCompactionFallsBackToTrimAtTriggerLine() {
        val windowConfig = config.copy(contextWindow = 900_000)
        val trigger = AgentContextBudget.triggerTokens(900_000)!!
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
            "摘要熔断后第二轮必须裁到触发线（$trigger）以内，实测 ${estimates.getOrNull(1)}",
            (estimates.getOrNull(1) ?: Int.MAX_VALUE) < trigger,
        )
        assertTrue(
            "硬裁剪必须可观测（HistoryTrimmed）",
            events.filterIsInstance<AgentEvent.HistoryTrimmed>().isNotEmpty(),
        )
    }

    /**
     * 压缩必须同时做到：逐字带过需求锚点、保留一段近期历史（`retainRatio` 预算）。
     *
     * 这是「80% 才压缩」能成立的前提——压缩次数少了，但每次都要保住需求原文与近期上下文，
     * 否则多代摘要会漂移，模型越来越看不懂用户到底要什么。
     */
    @Test
    fun compactionCarriesVerbatimAnchorAndKeepsRecentTail() {
        val anchor = "原始需求：把显示模块的压缩触发线改成窗口的八成，并且不要丢掉我的原始需求。"
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", "固定约束"))
        messages.put(AgentConversationCodec.userTextMessage(anchor))
        (1..40).forEach { turn ->
            messages.put(AgentConversationCodec.userTextMessage("第 $turn 轮追问"))
            messages.put(AgentConversationCodec.toJsonObject(
                AgentModelClient.ConversationMessage("assistant", "进展 $turn ".repeat(400)),
            ))
        }
        val before = messages.toString()
        val compacted = AgentContextCompactor(config, provider { _, _ -> response("## 原始需求与意图\n- 改触发线") }, AgentRunController())
            .compact(messages, 1, emptySet())
        val text = compacted.toString()
        assertTrue("锚点段必须带标题", text.contains(AgentContextCompactor.ANCHOR_HEADER))
        assertTrue("需求锚点必须逐字保留", text.contains(anchor))
        assertTrue("最近一轮必须逐字保留（retainRatio 预算）", text.contains("进展 40"))
        assertTrue("压缩后必须显著小于原文", compacted.toString().length < before.length)
    }

    /**
     * 多代检查点：上一代摘要原样保留、不被重写，只有更早的检查点才合并进新摘要。
     *
     * 这是「长对话不失忆」的第二道保险（第一道是需求锚点）：避免"摘要的摘要"每轮把
     * 上一代重写一遍而累积漂移。
     */
    @Test
    fun previousCheckpointIsKeptInsteadOfBeingRewritten() {
        val previous = AgentConversationCodec.toJsonObject(AgentModelClient.ConversationMessage(
            role = "assistant",
            content = "[Eta 上下文摘要：先前历史]\n旧检查点标记：阶段一已完成 A、B。\n\n" +
                "${AgentContextCompactor.ANCHOR_HEADER}\n把压缩策略做成八成触发",
            contextSummary = true,
        ))
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", "固定约束"))
        messages.put(previous)
        (1..40).forEach { turn ->
            messages.put(AgentConversationCodec.userTextMessage("第 $turn 轮追问"))
            messages.put(AgentConversationCodec.toJsonObject(
                AgentModelClient.ConversationMessage("assistant", "进展 $turn ".repeat(400)),
            ))
        }
        val compacted = AgentContextCompactor(config, provider { _, _ -> response("## 原始需求与意图\n- 新检查点") }, AgentRunController())
            .compact(messages, 1, emptySet())
        val summaries = (0 until compacted.length())
            .map { compacted.getJSONObject(it) }
            .filter { it.optBoolean("_eta_context_summary") }
        assertEquals("应保留旧检查点与新检查点各一条", 2, summaries.size)
        assertTrue(
            "旧检查点必须原样保留（不被重写）",
            summaries.first().optString("content").contains("旧检查点标记"),
        )
        assertTrue(
            "新检查点必须生成",
            summaries.last().optString("content").contains("新检查点"),
        )
        assertTrue("锚点必须继续逐字在场", compacted.toString().contains("把压缩策略做成八成触发"))
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
