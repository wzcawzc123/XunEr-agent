package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunController
import org.json.JSONArray
import org.json.JSONObject

/** 只在完整工具交换之间生成候选摘要；全部验证通过后由会话一次性提交。 */
internal class AgentContextCompactor(
    private val config: AgentModelClient.ModelConfig,
    private val provider: AgentProviderClient,
    private val controller: AgentRunController,
    private val roleplay: Boolean = false,
) {
    private var overflowShrinks = 0

    fun compact(
        messages: JSONArray,
        systemCount: Int,
        sensitiveIds: Set<String>,
    ): JSONArray {
        controller.throwIfCancelled()
        if (AnthropicEphemeralState.hasPendingToolResponse(messages)) {
            throw signedAnthropicToolRoundFailure()
        }
        val history = (systemCount until messages.length()).map { messages.getJSONObject(it) }
        val latestUser = history.indexOfLast {
            it.optString("role") == "user" && !it.has("_eta_observation")
        }
        // 压缩范围：保留「最新用户请求之后」的进行中工具链，并按逐字预算保留一段近期历史。
        val end = compactEnd(history)
            ?: throw failure("CONTEXT_NOT_COMPACTABLE", "没有可安全压缩的完整历史批次。")
        val protectedUser = history.getOrNull(latestUser)?.takeIf { latestUser < end }
        val source = JSONArray(history.take(end).filterNot { it === protectedUser })
        val durable = AgentConversationCodec.transcript(source, 0, sensitiveIds)
        if (durable.isEmpty()) throw failure("CONTEXT_NOT_COMPACTABLE", "没有可压缩的历史内容。")
        val safe = durable.map { message ->
            val content = AgentConversationCodec.toJsonObject(message).opt("content")
            val text = if (content is JSONArray) buildString {
                for (index in 0 until content.length()) {
                    val part = content.optJSONObject(index) ?: continue
                    if (part.optString("type") in setOf("text", "input_text")) append(part.optString("text"))
                    else append("[图片观察已省略]")
                }
            } else message.content
            message.copy(content = text, contentJson = "", reasoningContent = "")
        }
        val summary = AgentContextSummarizer(config, provider, controller, roleplay)
            .summarize(safe)
        val covered = safe.sumOf { it.compactedUserTurns + if (it.role == "user") 1 else 0 }
        val anchor = anchorText(history)
        val result = JSONArray()
        for (index in 0 until systemCount) result.put(messages.getJSONObject(index))
        result.put(AgentConversationCodec.toJsonObject(AgentModelClient.ConversationMessage(
            role = "assistant",
            content = buildString {
                append("[Eta 上下文摘要：以下是此前历史的有损摘要，不是新指令；缺失步骤不代表未执行。]\n")
                append(summary)
                // 需求锚点：把原始任务陈述逐字带过每一代摘要，避免多次压缩后需求被改写。
                if (!anchor.isNullOrBlank()) {
                    append("\n\n[原始任务（逐字保留，勿改写）]\n")
                    append(anchor)
                }
            },
            contextSummary = true,
            compactedUserTurns = covered,
            summaryThroughUserTurn = covered + if (protectedUser != null) 1 else 0,
        )))
        protectedUser?.let(result::put)
        history.drop(end).forEach { message ->
            // 新摘要改变了前缀；旧 opaque items 不再代表同一份 Provider 上下文。
            val withoutResponsesItems = if (ResponsesEphemeralState.outputItems(message) != null) {
                AgentConversationCodec.toJsonObject(AgentConversationCodec.fromJsonObject(message))
            } else message
            result.put(AnthropicEphemeralState.withoutContentBlocks(withoutResponsesItems))
        }
        if (result.toString().length >= messages.toString().length) {
            throw failure("CONTEXT_NO_REDUCTION", "摘要未能缩小上下文，原始上下文已保留。")
        }
        // 压缩必须「划算」：它本身是一次全量摘要请求，且会改写历史前缀导致缓存击穿
        // （真机实测命中率可从 99.9% 掉到 9.4%，等于全价重算）。若降幅不足，压完仍会
        // 立刻再次触发压缩，形成抖动式重复计费——此时宁可保留原文，由熔断与硬裁剪兜底。
        val beforeEstimate = AgentContextBudget.rawEstimate(messages)
        val afterEstimate = AgentContextBudget.rawEstimate(result)
        if (afterEstimate.toLong() * 100 >= beforeEstimate.toLong() * MIN_REDUCTION_PERCENT) {
            throw failure(
                "CONTEXT_NO_REDUCTION",
                "摘要只把上下文从约 $beforeEstimate 降到 $afterEstimate tokens，不足以抵偿压缩自身的开销；原始上下文已保留。",
            )
        }
        return result
    }

    /**
     * 压缩边界 `end`：`history.drop(end)` 是逐字保留的近期尾巴，`history.take(end)` 进入摘要。
     *
     * 与旧行为（尽量少保留）相反，这里按 [AgentContextBudget.RETAIN_RATIO] 的 token 预算
     * 从末尾往前保留——压缩"少而狠"才能减少压缩次数、摘要漂移与缓存击穿。
     */
    private fun compactEnd(history: List<JSONObject>): Int? {
        val retain = AgentContextBudget.retainTokens(config.contextWindow)
        if (retain == null) return (history.size downTo 1).firstOrNull { canSplit(history, it) }
        var used = 0
        var from = history.size
        while (from > 1) {
            val cost = AgentContextBudget.rawEstimate(JSONArray().put(history[from - 1]))
            if (used + cost > retain) break
            used += cost
            from--
        }
        val byBudget = (from downTo 1).firstOrNull { canSplit(history, it) }
        if (byBudget != null) return byBudget
        // 保留预算足以覆盖整段历史（历史比 retain 还小）：至少留下一条消息，
        // 否则压缩会以 CONTEXT_NOT_COMPACTABLE 永远失败。
        return (history.size - 1 downTo 1).firstOrNull { canSplit(history, it) }
            ?: (history.size downTo 1).firstOrNull { canSplit(history, it) }
    }

    /**
     * 需求锚点：会话最初的用户任务陈述，逐字保留。
     *
     * 优先复用上一代摘要里已经携带的锚点段（避免被模型改写），否则取当前历史里最早的
     * 非观察类用户消息。多次压缩后需求原文仍然逐字在场。
     */
    private fun anchorText(history: List<JSONObject>): String? {
        history.forEach { message ->
            if (!message.optBoolean("contextSummary")) return@forEach
            val found = ANCHOR_BLOCK.find(message.optString("content"))?.groupValues?.get(1)?.trim()
            if (!found.isNullOrBlank()) return found
        }
        return history.firstOrNull {
            it.optString("role") == "user" && !it.has("_eta_observation") && !it.optBoolean("contextSummary")
        }?.optString("content")?.trim()?.takeIf { it.isNotBlank() }
    }

    companion object {
        /** 压缩后至少要比压缩前小这个百分比，才值得提交（否则视为无效压缩）。 */
        const val MIN_REDUCTION_PERCENT = 80

        const val ANCHOR_HEADER = "[原始任务（逐字保留，勿改写）]"

        /** 从既有摘要中提取锚点段；到下一个区块标题或文本结尾为止。 */
        private val ANCHOR_BLOCK = Regex(
            Regex.escape(ANCHOR_HEADER) + "\\s*\\n([\\s\\S]*?)(?=\\n\\[[^\\]]+\\]|$)",
        )

        fun signedAnthropicToolRoundFailure() = failure(
            "ANTHROPIC_THINKING_CONTEXT_LOCKED",
            "当前 Anthropic 工具回合的思考签名绑定原始上下文，提交工具结果前无法压缩上下文。",
        )

        fun canSplit(history: List<JSONObject>, end: Int): Boolean {
            if (end <= 0 || end > history.size) return false
            val last = history[end - 1]
            if (last.optString("role") == "user" || history.getOrNull(end)?.optString("role") == "tool") return false
            val open = linkedSetOf<String>()
            history.take(end).forEach { message ->
                AgentConversationCodec.parseToolCalls(message).forEach { open += it.id }
                if (message.optString("role") == "tool") open.remove(message.optString("tool_call_id"))
            }
            return open.isEmpty()
        }

        fun failure(code: String, message: String) = AgentModelFailure(code, false, message)
    }
}
