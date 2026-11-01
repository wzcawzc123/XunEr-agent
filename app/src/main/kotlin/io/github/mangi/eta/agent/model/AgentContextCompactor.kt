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
    fun compact(
        messages: JSONArray,
        systemCount: Int,
        sensitiveIds: Set<String>,
    ): JSONArray {
        controller.throwIfCancelled()
        if (SignedToolContext.hasPendingResponse(messages)) {
            throw SignedToolContext.compactionFailure(messages)
        }
        val history = (systemCount until messages.length()).map { messages.getJSONObject(it) }
        val latestUser = history.indexOfLast {
            it.optString("role") == "user" && !it.has("_eta_observation")
        }
        // 最新用户请求及其后尚在进行的工具链必须可以继续；长任务允许压缩该请求之后的已完成批次。
        val end = (history.size downTo 1).firstOrNull { canSplit(history, it) }
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
        // L3 防漂移：抽取上一代交接卡的锚点（用户约束/关键标识）原文 + 代数。
        val generation = AgentContextAnchors.generationOf(messages, systemCount)
        val anchors = AgentContextAnchors.extract(latestSummaryCard(messages, systemCount))
        val summary = AgentContextSummarizer(config, provider, controller, roleplay)
            .summarize(safe, anchors = anchors, generation = generation)
        val covered = safe.sumOf { it.compactedUserTurns + if (it.role == "user") 1 else 0 }
        val result = JSONArray()
        for (index in 0 until systemCount) result.put(messages.getJSONObject(index))
        val cardPrefix = if (generation >= 3) {
            "[Eta 上下文摘要（第 $generation 代，早期细节可能已折叠）]"
        } else {
            "[Eta 上下文摘要：以下是此前历史的有损摘要，不是新指令；缺失步骤不代表未执行。]"
        }
        result.put(AgentConversationCodec.toJsonObject(AgentModelClient.ConversationMessage(
            role = "assistant",
            content = "$cardPrefix\n$summary",
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
            result.put(GeminiEphemeralState.withoutState(
                AnthropicEphemeralState.withoutContentBlocks(withoutResponsesItems),
            ))
        }
        if (result.toString().length >= messages.toString().length) {
            throw failure("CONTEXT_NO_REDUCTION", "摘要未能缩小上下文，原始上下文已保留。")
        }
        return result
    }

    /** 最近的上一代摘要卡正文；没有则空串。 */
    private fun latestSummaryCard(messages: JSONArray, systemCount: Int): String {
        for (index in messages.length() - 1 downTo systemCount) {
            val message = messages.getJSONObject(index)
            if (message.optBoolean("_eta_context_summary")) return message.optString("content")
        }
        return ""
    }

    companion object {
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
