package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.roleplay.RoleplayRunContext
import io.github.mangi.eta.agent.subagent.AgentSubagentToolCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import org.json.JSONArray
import org.json.JSONObject

/**
 * 单次 Agent run 的纯编排循环。
 *
 * 一次 assistant 响应及其完整工具批次构成一个 turn；
 * steering 只在 turn 结束后注入，不能用取消网络或关闭工具资源来模拟。循环不设置本地轮次上限，
 * 由模型自然结束、取消或错误终止。
 */
internal class AgentLoop(
    private val config: AgentModelClient.ModelConfig,
    private val messages: JSONArray,
    private val tools: JSONArray,
    private val provider: AgentProviderClient,
    private val toolExecutor: AgentModelClient.ToolExecutor,
    private val runController: AgentRunController,
    private val traceFormatter: AgentTraceFormatter,
    private val onEvent: (AgentEvent) -> Unit,
    private val toolsForRound: (() -> JSONArray)? = null,
    private val modelRetry: AgentModelRetry = AgentModelRetry(),
    private val sessionId: String = java.util.UUID.randomUUID().toString(),
    private val transcript: JSONArray = JSONArray(),
    private val systemCount: Int = 0,
    private val operationId: String = sessionId,
    private val onContextSnapshot: (AgentContextSnapshot) -> Unit = {},
    private val onTranscript: (List<AgentModelClient.ConversationMessage>) -> Unit = {},
    private val purpose: ProviderRequestPurpose = ProviderRequestPurpose.CHAT,
    private val roleplayContext: RoleplayRunContext? = null,
    initialSupplementIndex: Int = 0,
) {
    data class Result(
        val content: String,
        val reasoningContent: String,
        val sensitiveToolCallIds: Set<String>,
    )

    private data class ToolOutcome(
        val call: AgentModelClient.ToolCall,
        val result: AgentModelClient.ToolResult,
    )

    private var availableToolNames = emptySet<String>()
    private val accumulatedReasoning = StringBuilder()
    private val sensitiveToolCallIds = linkedSetOf<String>()
    private val toolBatchLock = Any()
    private var pendingToolImageMessage: JSONObject? = null
    /**
     * 重复调用检测：连续用**完全相同的参数**调同一个工具，通常意味着模型在原地打转
     * （改了没生效、看错了输出、或忘了已经试过）。
     *
     * 只提醒、不拦截：有些工具确实需要幂等重试（等页面加载完再点），硬阻断会误伤。
     */
    private var lastToolSignature: String? = null
    private var repeatToolStreak = 0
    private var pendingRepeatNudge: String? = null
    private val context = AgentContextSession(
        config, messages, systemCount, operationId, provider, runController,
        { sensitiveToolCallIds }, onEvent, onContextSnapshot, { transcript.length() },
        roleplay = roleplayContext != null,
    )
    private var supplementIndex = initialSupplementIndex

    /** 连续空回合的纠偏计数；出现工具进展会复位。 */
    private var emptyRoundStreak = 0
    private var pendingEmptyRoundNudge: String? = null

    fun contextSnapshot(): AgentContextSnapshot? = context.snapshot()

    private fun appendMessage(message: JSONObject) {
        messages.put(message)
        transcript.put(message)
    }

    private var publishedTranscriptSize = 0

    private fun publishTranscript() {
        if (publishedTranscriptSize == transcript.length()) return
        onTranscript(AgentConversationCodec.transcript(transcript, 0, sensitiveToolCallIds))
        publishedTranscriptSize = transcript.length()
    }

    fun compactOnly(): Result {
        context.compact(force = true)
        return Result("", "", emptySet())
    }

    fun reasoningSnapshot(): String = accumulatedReasoning.toString().trim()

    fun sensitiveToolCallIdsSnapshot(): Set<String> = sensitiveToolCallIds.toSet()

    fun run(): Result {
        var round = 1
        var precedingTools: JSONArray? = null

        while (true) {
            runController.throwIfCancelled()
            if (purpose.allowsTools) appendPendingSteeringMessage()

            // 带签名的工具链沿用发出调用时的请求前缀，工具链完成后再刷新目录。
            val roundTools = if (SignedToolContext.hasPendingResponse(messages)) {
                precedingTools ?: tools
            } else if (purpose.allowsTools) {
                toolsForRound?.invoke() ?: tools
            } else {
                JSONArray()
            }
            precedingTools = roundTools
            availableToolNames = (0 until roundTools.length()).mapNotNullTo(mutableSetOf()) { index ->
                roundTools.optJSONObject(index)?.optJSONObject("function")?.optString("name")
            }
            publishTranscript()
            if (purpose.allowsTools) {
                try {
                    context.compact()
                } catch (failure: AgentModelFailure) {
                    // M1.2 可恢复性：摘要压缩失败（如 CONTEXT_NO_REDUCTION）不再整 run 毙命，
                    // 降级交给下方窗口级硬裁剪；只有裁剪后仍装不下才以 CONTEXT_EXHAUSTED 收束。
                    // 取消走 AgentRunCancelledException(RuntimeException)，不在本捕获范围；
                    // session 已发 ContextCompaction failed 事件，此处不重复。
                    runController.throwIfCancelled()
                }
            }
            var requestMessages = AssistantScreenContextProjection.project(
                roleplayContext?.projectMessages(messages) ?: messages,
            )
            // 零模型的工具结果修剪（确定性、幂等，只改本次请求副本）。
            // 修剪后内容落在预算内，重复执行不再变化，每轮前缀一致，不牺牲缓存命中。
            requestMessages = AgentToolResultPruner.pruneRequestMessages(requestMessages)
            // 纯文本模型（supportsVision=false）：出站前剥离全部图片块（初始附件与循环中工具截图都覆盖）。
            // 只改出站副本，持久 messages 保持原样；剥离后预算估算口径与真实请求一致。
            if (!config.supportsVision) {
                requestMessages = AgentConversationCodec.stripImagesForTextOnlyModel(requestMessages)
            }
            // 兜底：摘要压缩后仍超窗时，硬裁剪非系统历史，避免带着必然失败的重型请求发出。
            // 审查 C1（v3.4.0-audit 探针实证）：原门要求 usageObserved，导致新鲜 run 的 round1
            //（尚无用量回执）巨型历史原样发出、trim 与降级同轮被挡；估量口径与轮次无关，故去前置。
            // R6：摘要已熔断时不能再指望压缩降规模，把裁剪线从窗口降到触发线。
            val window = config.contextWindow
            val trimWindow = if (context.compactionBlockedForRun) {
                AgentContextBudget.triggerTokens(window) ?: window
            } else {
                window
            }
            val expectedTokens = context.budgetEstimate(requestMessages, roundTools)
            if (trimWindow != null && trimWindow > 0 && expectedTokens >= trimWindow &&
                // H1 守卫：签名回合内禁止裁剪——硬裁剪会把消息重建成无签名形态，
                // 上游判定"改动了 thinking 块"，后续每轮 400。工具结果提交后 pending 解除。
                !AnthropicEphemeralState.hasPendingToolResponse(messages)
            ) {
                // roleplay 固定开销（persona/worldbook 注入可达数万 token）必须计入 systemEstimate，
                // 否则 limit 偏大 → 保留过多 → 投影后仍超线 → CONTEXT_EXHAUSTED 死角。
                val systemEstimate = run {
                    val projected = AssistantScreenContextProjection.project(
                        roleplayContext?.projectMessages(messages) ?: messages,
                    )
                    val historyOnly = JSONArray()
                    for (index in systemCount until projected.length()) historyOnly.put(projected.getJSONObject(index))
                    (context.budgetEstimate(projected) - context.budgetEstimate(historyOnly)).coerceAtLeast(0)
                }
                // 直接在原始 JSONObject 上裁剪，保留 _eta_* 瞬态键
                //（思考签名块、观察标记、屏幕上下文）——ConversationMessage 往返会剥掉它们。
                val history = (systemCount until messages.length()).map { messages.getJSONObject(it) }
                val trimOutcome = AgentHistoryTrimmer.trimJson(
                    history = history,
                    window = trimWindow,
                    systemEstimate = systemEstimate,
                    calibration = context.calibrationValue(),
                )
                if (trimOutcome.trimmed) {
                    // 硬裁剪必须可观测 —— UI/日志能区分"摘要压缩"与"硬裁剪丢历史"。
                    onEvent(AgentEvent.HistoryTrimmed(operationId, trimOutcome.droppedMessages))
                }
                while (messages.length() > systemCount) messages.remove(messages.length() - 1)
                trimOutcome.messages.forEach { messages.put(it) }
                requestMessages = AssistantScreenContextProjection.project(
                    roleplayContext?.projectMessages(messages) ?: messages,
                )
                // 裁剪后重放 prune（幂等），保证预算估算口径与真实请求一致。
                requestMessages = AgentToolResultPruner.pruneRequestMessages(requestMessages)
                // 裁剪后再次剥离：硬裁剪会保留原 JSONObject，图片块可能重新进入请求。
                if (!config.supportsVision) {
                    requestMessages = AgentConversationCodec.stripImagesForTextOnlyModel(requestMessages)
                }
                val afterTrimTokens = context.budgetEstimate(requestMessages, roundTools)
                if (afterTrimTokens >= trimWindow) {
                    throw AgentModelFailure(
                        "CONTEXT_EXHAUSTED",
                        false,
                        "上下文在摘要压缩与硬裁剪后仍超过窗口(约 " +
                            "$afterTrimTokens ≥ $trimWindow)。" +
                            "请新开会话继续任务，或先手动压缩历史；本会话记录未丢失。",
                    )
                }
            }
            // 纠偏提醒只随下一轮请求发出，不写回持久历史。
            // 每轮至多消费一条（重复调用优先于空回合）：两条同时注入会让一轮出现两个 user 提醒。
            val pendingNudge = pendingRepeatNudge ?: pendingEmptyRoundNudge
            if (pendingNudge != null) {
                if (pendingRepeatNudge != null) pendingRepeatNudge = null else pendingEmptyRoundNudge = null
                requestMessages = JSONArray().also { merged ->
                    for (index in 0 until requestMessages.length()) merged.put(requestMessages.get(index))
                    merged.put(AgentConversationCodec.userTextMessage(pendingNudge))
                }
            }
            var roundInputTokens: Int? = null
            val reasoningLengthBeforeRound = accumulatedReasoning.length
            val completedRound = try {
                modelRetry.complete(
                    initialRound = round,
                    request = ProviderRequest(config, requestMessages, roundTools, sessionId, purpose),
                    provider = provider,
                    controller = runController,
                    onEvent = { event ->
                        if (event is AgentEvent.RoundStarted) roundInputTokens = null
                        onEvent(event)
                    },
                    onProviderEvent = { attemptRound, providerEvent ->
                        if (!purpose.allowsTools && (providerEvent is ProviderEvent.HostedToolStarted ||
                                providerEvent is ProviderEvent.SearchSuggestions ||
                                providerEvent is ProviderEvent.BlockStart && providerEvent.kind == AssistantBlockKind.TOOL_CALL)) {
                            throw AgentModelFailure("REPLY_REWRITE_TOOL_CALL", false, "改写回复时模型请求了工具，已停止；原回复未改变。")
                        }
                        if (providerEvent is ProviderEvent.Usage) {
                            roundInputTokens = providerEvent.contextInputTokens ?: roundInputTokens
                        }
                        if (providerEvent is ProviderEvent.BlockDelta &&
                            providerEvent.kind == AssistantBlockKind.THINKING
                        ) {
                            accumulatedReasoning.append(providerEvent.delta)
                        }
                        providerEvent.toAgentEvent(attemptRound)?.let(onEvent)
                    },
                    discardAttemptReasoning = { accumulatedReasoning.setLength(reasoningLengthBeforeRound) },
                ).also { response ->
                    rejectContentFilter(response.response)
                    if (purpose == ProviderRequestPurpose.CHAT) validateChatResponse(response.response)
                }
            } finally {
                // 同一回合的重试仍需原始观察；整个回合结束后才移除截图。
                discardPendingToolImageMessage()
            }
            context.observeInputTokens(roundInputTokens)
            // 用本轮实测校准本地估算；估算只影响判据与裁剪，不改变实际发送内容。
            // （4.0 重建时该调用随上游 AgentLoop 覆盖丢失，校准馈线断成死参数——别再丢。）
            context.observeBudget(roundInputTokens, AgentContextBudget.rawEstimate(requestMessages, roundTools))
            round = completedRound.round
            val providerResponse = completedRound.response

            runController.throwIfCancelled()
            val assistantMessage = providerResponse.assistantMessage
            val toolCalls = AgentConversationCodec.parseToolCalls(assistantMessage)
            if (!purpose.allowsTools && toolCalls.isNotEmpty()) {
                throw AgentModelFailure("REPLY_REWRITE_TOOL_CALL", false, "改写回复时模型请求了工具，已停止；原回复未改变。")
            }
            if (purpose == ProviderRequestPurpose.REPLY_REWRITE && providerResponse.stopReason != AssistantStopReason.END_TURN) {
                throw AgentModelFailure("REPLY_REWRITE_INCOMPLETE", false, "模型未返回完整的改写回复；原回复未改变。")
            }
            val assistantReasoning = assistantMessage.optString("reasoning_content")
            if (
                assistantReasoning.isNotBlank() &&
                accumulatedReasoning.length == reasoningLengthBeforeRound
            ) {
                accumulatedReasoning.append(assistantReasoning)
            }

            appendMessage(
                AgentConversationCodec.assistantHistoryMessage(
                    source = assistantMessage,
                    toolCalls = toolCalls,
                ).put("_eta_message_id", "assistant-$operationId-$round")
            )
            onEvent(
                AgentEvent.AssistantReceived(
                    round = round,
                    contentChars = assistantMessage.optString("content").length,
                    reasoningContent = assistantReasoning,
                    toolNames = toolCalls.map { it.name },
                )
            )

            if (toolCalls.isNotEmpty()) {
                val outcomes = executeToolBatch(round, toolCalls, providerResponse.stopReason)
                outcomes.forEach { outcome ->
                    appendMessage(AgentConversationCodec.toolResultMessage(outcome.call, outcome.result))
                }
                publishTranscript()
                appendToolImages(round, outcomes)
                // 工具跑出了实际进展：空回合计数复位，避免跨越正常回合累计。
                emptyRoundStreak = 0
                publishTranscript()
                round += 1
                continue
            }

            publishTranscript()

            // assistant 已自然结束时再检查 steering。这样补充消息不会丢掉刚完成的回答。
            if (purpose.allowsTools && appendPendingSteeringOrSeal()) {
                round += 1
                continue
            }

            val content = assistantMessage.optString("content").trim()
            if (content.isBlank() || content == "null") {
                val reasoned = assistantMessage.optString("reasoning_content").isNotBlank()
                if (reasoned) {
                    // 产生了思考却没有正文：本回合没有任何产出，先纠偏重试；
                    // 预算用尽后按「运行已完成但没有返回文字」收尾，
                    // 而不是让整次运行失败、丢掉此前已完成的工具工作。
                    emptyRoundStreak += 1
                    if (emptyRoundStreak <= MAX_EMPTY_ROUND_NUDGES) {
                        pendingEmptyRoundNudge = EMPTY_ROUND_NUDGE
                        round += 1
                        continue
                    }
                    onEvent(AgentEvent.RunFinished(round = round, contentChars = 0))
                    return Result(
                        content = "",
                        reasoningContent = reasoningSnapshot(),
                        sensitiveToolCallIds = sensitiveToolCallIds.toSet(),
                    )
                }
                val finishReason = assistantMessage.optString("finish_reason")
                error("模型接口第 $round 轮返回为空${finishReason.takeIf { it.isNotBlank() }?.let { "：$it" }.orEmpty()}")
            }

            publishTranscript()
            if (purpose.allowsTools) context.compact(final = true)
            onEvent(AgentEvent.RunFinished(round = round, contentChars = content.length))
            return Result(
                content = content,
                reasoningContent = reasoningSnapshot(),
                sensitiveToolCallIds = sensitiveToolCallIds.toSet(),
            )
        }
    }

    /**
     * 拒答或过滤会在流中途截断回复，半截正文、思考块和工具调用都不能进入上下文：
     * 带着被截断的签名块继续请求会被上游判定为改动了 thinking 块，同一会话随后每轮都会 400。
     * 因此不写入 history、不执行工具，直接结束本次运行，由用户改写请求或换模型。
     */
    private fun rejectContentFilter(response: ProviderResponse) {
        if (response.stopReason != AssistantStopReason.CONTENT_FILTER) return
        val explanation = response.assistantMessage.optJSONObject("stop_details")
            ?.let { details -> details.optString("explanation").takeUnless { details.isNull("explanation") } }
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        throw AgentModelFailure(
            "MODEL_CONTENT_FILTER",
            false,
            buildString {
                append("模型服务商拦截了本次回复，未完成的内容已丢弃。")
                explanation?.let { append("服务商说明：").append(it) }
                append("请修改或删除触发拦截的请求后重试，或换用其他模型。")
            },
        )
    }

    private fun validateChatResponse(response: ProviderResponse) {
        val message = response.assistantMessage
        if (AgentConversationCodec.parseToolCalls(message).isNotEmpty()) return
        val content = message.optString("content").trim()
        if (content.isNotBlank() && content != "null") return
        // 只有「产生了思考却没有正文」的正常结束才走纠偏预算；
        // 纯空响应（无思考）保持上游语义：按 MODEL_EMPTY_RESPONSE 失败。
        if (response.stopReason == AssistantStopReason.END_TURN &&
            emptyRoundStreak <= MAX_EMPTY_ROUND_NUDGES &&
            message.optString("reasoning_content").isNotBlank()
        ) {
            return
        }
        throw when (response.stopReason) {
            AssistantStopReason.OUTPUT_LIMIT ->
                AgentModelFailure("MODEL_OUTPUT_LIMIT", false, "模型输出额度已耗尽但未生成正文，请检查输出上限或降低思考强度。")
            else -> AgentModelFailure("MODEL_EMPTY_RESPONSE", false, "模型未返回正文或工具调用，请检查服务商状态。")
        }
    }

    private fun appendPendingSteeringMessage(): Boolean {
        val supplement = runController.pollSteeringMessage() ?: return false
        appendMessage(steeringMessage(supplement))
        context.userAppended()
        return true
    }

    private fun appendPendingSteeringOrSeal(): Boolean {
        val supplement = runController.pollSteeringOrSeal() ?: return false
        appendMessage(steeringMessage(supplement))
        context.userAppended()
        return true
    }

    private fun steeringPrompt(supplement: String): String =
        "用户补充指令：$supplement\n\n请基于当前任务上下文继续执行，不要从头重复已经完成或已经验证过的操作。"

    private fun steeringMessage(supplement: String): JSONObject =
        AgentConversationCodec.userTextMessage(steeringPrompt(supplement))
            .put("_eta_message_id", "user-$operationId-supplement-${++supplementIndex}")

    private fun executeToolBatch(
        round: Int,
        toolCalls: List<AgentModelClient.ToolCall>,
        stopReason: AssistantStopReason,
    ): List<ToolOutcome> {
        if (stopReason != AssistantStopReason.TOOL_USE) {
            return toolCalls.map { call ->
                when (stopReason) {
                    AssistantStopReason.OUTPUT_LIMIT -> rejectedToolOutcome(
                        round, call, "TRUNCATED_TOOL_CALL",
                        "模型输出达到长度上限，工具参数可能不完整；本次调用未执行，请重新提交完整参数。",
                    )
                    else -> rejectedToolOutcome(
                        round, call, "UNEXPECTED_TOOL_CALL",
                        "模型在 ${stopReason.name} 终止状态下返回了工具调用；本批调用未执行，请重新规划。",
                    )
                }
            }
        }

        val outcomes = mutableListOf<ToolOutcome>()
        var index = 0
        while (index < toolCalls.size) {
            val current = toolCalls[index]
            if (current.name == AgentSubagentToolCatalog.TOOL_NAME && current.name in availableToolNames) {
                val parallelBatch = mutableListOf<AgentModelClient.ToolCall>()
                while (
                    index < toolCalls.size &&
                    toolCalls[index].name == AgentSubagentToolCatalog.TOOL_NAME &&
                    toolCalls[index].name in availableToolNames
                ) {
                    parallelBatch += toolCalls[index].copy(round = round)
                    index += 1
                }
                if (parallelBatch.size == 1) {
                    outcomes += executeTool(round, parallelBatch.single())
                } else {
                    outcomes += executeParallelSubagents(round, parallelBatch)
                }
            } else {
                outcomes += executeTool(round, current.copy(round = round))
                index += 1
            }
        }
        return outcomes
    }

    private fun executeParallelSubagents(
        round: Int,
        calls: List<AgentModelClient.ToolCall>,
    ): List<ToolOutcome> {
        runController.throwIfCancelled()
        calls.forEach { toolCall ->
            synchronized(toolBatchLock) {
                onEvent(
                    AgentEvent.ToolStarted(
                        round = round,
                        toolCallId = toolCall.id,
                        name = toolCall.name,
                        argsPreview = traceFormatter.summarizeArguments(toolCall),
                        command = traceFormatter.displayCommand(toolCall),
                    ),
                )
            }
        }
        val outcomes = runBlocking(Dispatchers.IO) {
            supervisorScope {
                calls.map { toolCall ->
                    async {
                        val result = try {
                            toolExecutor.execute(toolCall)
                        } catch (throwable: Exception) {
                            if (runController.isCancelled) {
                                AgentModelClient.ToolResult(
                                    content = JSONObject()
                                        .put("ok", false)
                                        .put("code", "SUBAGENT_CANCELLED")
                                        .put("message", "已停止")
                                        .toString(),
                                )
                            } else {
                                AgentModelClient.ToolResult(
                                    content = JSONObject()
                                        .put("ok", false)
                                        .put("code", "TOOL_ERROR")
                                        .put("message", throwable.message ?: throwable.javaClass.simpleName)
                                        .toString(),
                                )
                            }
                        }
                        synchronized(toolBatchLock) {
                            if (result.sensitive || AgentSensitiveToolPolicy.isSensitive(toolCall.name)) {
                                sensitiveToolCallIds += toolCall.id
                            }
                            if (!runController.isCancelled) {
                                emitToolFinished(round, toolCall, result)
                            }
                        }
                        ToolOutcome(toolCall, result)
                    }
                }.awaitAll()
            }
        }
        runController.throwIfCancelled()
        return outcomes
    }

    /**
     * 记录本次工具调用的「工具名 + 规范化参数」签名，连续重复时排入提醒。
     *
     * 规范化：折叠空白，避免「同样命令只因格式微差就绕过检测」。
     * 只保留最近一次签名——中间夹了别的调用就说明不是死循环，计数归零。
     */
    private fun trackRepeatedToolCall(toolCall: AgentModelClient.ToolCall) {
        val signature = toolCall.name + "|" + toolCall.argumentsJson.trim().replace(Regex("\\s+"), " ")
        if (signature == lastToolSignature) {
            repeatToolStreak += 1
        } else {
            lastToolSignature = signature
            repeatToolStreak = 1
            return
        }
        if (repeatToolStreak == REPEAT_NUDGE_THRESHOLD) {
            pendingRepeatNudge = REPEAT_TOOL_NUDGE.format(toolCall.name, repeatToolStreak)
        }
    }

    private fun executeTool(
        round: Int,
        toolCall: AgentModelClient.ToolCall,
    ): ToolOutcome {
        runController.throwIfCancelled()
        if (toolCall.name !in availableToolNames) {
            return rejectedToolOutcome(
                round = round,
                toolCall = toolCall,
                code = "TOOL_NOT_AVAILABLE",
                message = "工具未在本轮能力目录中提供，请使用当前可用工具",
            )
        }
        val roundCall = if (toolCall.round == round) toolCall else toolCall.copy(round = round)
        trackRepeatedToolCall(roundCall)
        onEvent(
            AgentEvent.ToolStarted(
                round = round,
                toolCallId = roundCall.id,
                name = roundCall.name,
                argsPreview = traceFormatter.summarizeArguments(roundCall),
                command = traceFormatter.displayCommand(roundCall),
            )
        )

        val result = try {
            toolExecutor.execute(roundCall)
        } catch (throwable: Exception) {
            runController.throwIfCancelled()
            AgentModelClient.ToolResult(
                content = JSONObject()
                    .put("ok", false)
                    .put("code", "TOOL_ERROR")
                    .put("message", throwable.message ?: throwable.javaClass.simpleName)
                    .toString(),
            )
        }
        if (result.sensitive || AgentSensitiveToolPolicy.isSensitive(roundCall.name)) {
            sensitiveToolCallIds += roundCall.id
        }

        emitToolFinished(round, roundCall, result)
        return ToolOutcome(roundCall, result)
    }

    private fun rejectedToolOutcome(
        round: Int,
        toolCall: AgentModelClient.ToolCall,
        code: String,
        message: String,
    ): ToolOutcome {
        onEvent(
            AgentEvent.ToolStarted(
                round = round,
                toolCallId = toolCall.id,
                name = toolCall.name,
                argsPreview = traceFormatter.summarizeArguments(toolCall),
                command = traceFormatter.displayCommand(toolCall),
            )
        )
        val result = AgentModelClient.ToolResult(
            content = JSONObject()
                .put("ok", false)
                .put("code", code)
                .put("message", message)
                .toString(),
            sensitive = AgentSensitiveToolPolicy.isSensitive(toolCall.name),
        )
        if (result.sensitive) sensitiveToolCallIds += toolCall.id
        emitToolFinished(round, toolCall, result)
        return ToolOutcome(toolCall, result)
    }

    private fun emitToolFinished(
        round: Int,
        toolCall: AgentModelClient.ToolCall,
        result: AgentModelClient.ToolResult,
    ) {
        onEvent(
            AgentEvent.ToolFinished(
                round = round,
                toolCallId = toolCall.id,
                name = toolCall.name,
                resultSummary = traceFormatter.summarizeResult(toolCall.name, result),
                imageCount = result.images.size,
                imageBytes = result.images.sumOf { it.bytes },
                success = traceFormatter.isSuccessResult(result),
                command = result.command,
            )
        )
    }

    private fun appendToolImages(
        round: Int,
        outcomes: List<ToolOutcome>,
    ) {
        // 每个已完成结果立即落盘；图片观察仍统一放在完整工具批次之后。
        val imageOutcomes = outcomes.filter { outcome -> outcome.result.images.isNotEmpty() }
        if (imageOutcomes.isEmpty()) {
            return
        }

        // 工具截图是瞬时观察，不是会话资产。下一次思考消费后立即删除。
        discardPendingToolImageMessage()
        val images = imageOutcomes.flatMap { outcome -> outcome.result.images }
        val toolNames = imageOutcomes
            .map { outcome -> outcome.call.name }
            .distinct()
            .joinToString(", ")
        pendingToolImageMessage = AgentConversationCodec.userMessage(
            text = "图片观察：以下 ${images.size} 张图片是工具 $toolNames 刚刚实际返回的结果，" +
                "是本次对话中最新的图片内容。用户之前发送的附件图片不是工具返回，不要把它们当成工具结果。" +
                "若与历史图片或用户附件混淆，以本条消息内的图片为准。",
            images = images,
        ).put("_eta_observation", true).also(messages::put)

        imageOutcomes.forEach { outcome ->
            onEvent(
                AgentEvent.ToolImagesAttached(
                    round = round,
                    toolName = outcome.call.name,
                    imageCount = outcome.result.images.size,
                    imageBytes = outcome.result.images.sumOf { it.bytes },
                )
            )
        }
    }

    private fun discardPendingToolImageMessage() {
        val pending = pendingToolImageMessage ?: return
        pendingToolImageMessage = null
        for (index in messages.length() - 1 downTo 0) {
            if (messages.optJSONObject(index) === pending) {
                messages.remove(index)
                return
            }
        }
    }

    private fun ProviderEvent.toAgentEvent(round: Int): AgentEvent? =
        when (this) {
            ProviderEvent.RequestStarted -> AgentEvent.ProviderRequestStarted(round)
            is ProviderEvent.ResponseHeaders -> AgentEvent.ProviderResponseStarted(round, httpCode)
            is ProviderEvent.BlockStart -> AgentEvent.AssistantBlockStart(
                round = round,
                kind = kind.toRuntimeKind(),
                index = index,
                blockId = blockId,
                name = name,
            )
            is ProviderEvent.BlockDelta -> AgentEvent.AssistantBlockDelta(
                round = round,
                kind = kind.toRuntimeKind(),
                index = index,
                deltaChars = delta.length,
                delta = delta,
            )
            is ProviderEvent.BlockEnd -> AgentEvent.AssistantBlockEnd(
                round = round,
                kind = kind.toRuntimeKind(),
                index = index,
                blockId = blockId,
                name = name,
                contentChars = content.length,
                replacementContent = content.takeIf { replaceContent },
            )
            is ProviderEvent.Usage -> AgentEvent.UsageReceived(round = round, usage = usage)
            is ProviderEvent.HostedToolStarted -> AgentEvent.HostedToolStarted(
                round = round,
                toolCallId = id,
                name = name,
            )
            is ProviderEvent.HostedToolFinished -> AgentEvent.HostedToolFinished(
                round = round,
                toolCallId = id,
                name = name,
                success = success,
            )
            is ProviderEvent.SearchSuggestions -> AgentEvent.SearchSuggestions(round, index, html)
            is ProviderEvent.Completed -> null
        }

    private fun AssistantBlockKind.toRuntimeKind(): AgentEvent.AssistantBlockKind =
        when (this) {
            AssistantBlockKind.TEXT -> AgentEvent.AssistantBlockKind.TEXT
            AssistantBlockKind.THINKING -> AgentEvent.AssistantBlockKind.THINKING
            AssistantBlockKind.TOOL_CALL -> AgentEvent.AssistantBlockKind.TOOL_CALL
        }

}

/** 连续多少次完全相同的调用才提醒（3 次：1 次可能是巧合，2 次可能还在等状态）。 */
/** 连续空回合的纠偏上限；用尽后按「没有返回文字」自然收尾。 */
private const val MAX_EMPTY_ROUND_NUDGES = 2
private const val EMPTY_ROUND_NUDGE =
    "上一轮只产生了思考内容，既没有正文也没有工具调用。" +
        "请直接输出给用户的答复正文；如果任务还需要继续执行，请给出工具调用。"

private const val REPEAT_NUDGE_THRESHOLD = 3

private const val REPEAT_TOOL_NUDGE =
    "[系统提醒] 你已经连续 %2\$d 次用完全相同的参数调用 %1\$s，输出不会变化。" +
        "如果是在等状态变化，请先确认状态确实变了；否则换一种方法，或说明卡在哪里。"
