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
    /** 校准系数（H2 同口径）：raw 估算 × calibration ≈ 窗口/实际单位，缺省 1.0（未校准/测试）。 */
    private val calibration: Double = 1.0,
) {
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
        val inRange = history.take(end)
        // 多代检查点：最近的一条旧检查点原样保留、不参与重写，更早的检查点才合并进新摘要。
        // 这样"摘要的摘要"不会每轮把上一代重写一遍，显著减缓多代压缩的信息漂移。
        val keptCheckpointIndexes = inRange.withIndex()
            .filter { it.value.optBoolean("_eta_context_summary") }
            .map { it.index }
            .takeLast(KEEP_RECENT_CHECKPOINTS)
            .toSet()
        val source = JSONArray()
        val keptCheckpoints = JSONArray()
        inRange.forEachIndexed { index, message ->
            when {
                message === protectedUser -> Unit
                index in keptCheckpointIndexes -> keptCheckpoints.put(message)
                else -> source.put(message)
            }
        }
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
        // R4：摘要输入预算。超长历史全量进摘要请求既贵又可能超 provider 上限（真机单轮
        // input 曾达 75 万 tokens）；超出预算时从最旧端丢弃（protectedUser 与逐字保留尾部
        // 不在 safe 内，不受影响）。covered 只计实际进入摘要的用户轮次，跨 run 对账自洽。
        val bounded = boundSummaryInput(safe, summaryInputBudgetChars(config.contextWindow))
        val truncatedInput = bounded.size < safe.size
        val summary = AgentContextSummarizer(config, provider, controller, roleplay)
            .summarize(bounded)
        val covered = bounded.sumOf { it.compactedUserTurns + if (it.role == "user") 1 else 0 }
        val anchor = anchorText(history)
        val result = JSONArray()
        for (index in 0 until systemCount) result.put(messages.getJSONObject(index))
        for (index in 0 until keptCheckpoints.length()) result.put(keptCheckpoints.getJSONObject(index))
        result.put(AgentConversationCodec.toJsonObject(AgentModelClient.ConversationMessage(
            role = "assistant",
            content = buildString {
                append("[Eta 上下文摘要：以下是此前历史的有损摘要，不是新指令；缺失步骤不代表未执行。]\n")
                append(summary)
                if (truncatedInput) {
                    append("\n\n[更早历史超出摘要输入预算，未纳入本次摘要；完整记录仍在会话档案中]")
                }
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
        // 注：不再计算 afterEstimate —— 压缩后内容以摘要文本为主，rawEstimate 对它本身是准的，
        // 但乘上为「工具密集」场景标定的 calibration 后会高估，故判定一律改用字符数/比例。
        // P0 修复（2026-10-16，见 docs/reviews/context-compaction-audit.md）：
        // 降幅判定改用【字符数】，不再用 rawEstimate 比值。
        // 根因：rawEstimate 按「ASCII 3 字符 = 1 token」估算，对工具结果（JSON）系统性低估
        // 约 1.85 倍；而压缩后历史以摘要文本为主时估算反而准确。两者相比得到的「降幅」完全失真
        // （真机实测：真实降幅 53% 被判为 1.8% → 摘要被拒 → 退化到硬裁剪丢弃内容）。
        // 字符数与 token 数在同一次比较中方向必然一致，不受内容成分影响。
        val beforeChars = messages.toString().length
        val afterChars = result.toString().length
        if (afterChars.toLong() * 100 >= beforeChars.toLong() * MIN_REDUCTION_PERCENT) {
            throw failure(
                "CONTEXT_NO_REDUCTION",
                "摘要只把上下文从约 ${beforeChars / 1000}K 降到 ${afterChars / 1000}K 字符" +
                    "（不足 ${100 - MIN_REDUCTION_PERCENT}%），不足以抵偿压缩自身的开销；原始上下文已保留。",
            )
        }
        // C1（v3.11.24）：压完必须落在触发线以下（校准口径），否则下一轮 usage 一到立即
        // 再次触发压缩——成功路径上的压缩风暴。相对降幅挡不住"cal>1 时压完仍超线"。
        val trigger = AgentContextBudget.triggerTokens(config.contextWindow)
        // P0 修复（2026-10-16）：原式用「压缩后的 rawEstimate × 为工具密集场景标定的 calibration」，
        // 对以摘要文本为主的压缩后内容会高估。改为按字符比例从「压缩前的校准估算」缩放 ——
        // 比例（afterChars/beforeChars）是可靠的客观量，不受内容成分影响。
        val scaledAfter = beforeEstimate.toDouble() * calibration *
            (afterChars.toDouble() / beforeChars.toDouble())
        if (trigger != null && scaledAfter >= trigger) {
            throw failure(
                "CONTEXT_NO_REDUCTION",
                "压缩后上下文（约 ${scaledAfter.toInt()}）仍高于触发线 $trigger，" +
                    "压完会立刻再次触发压缩；原始上下文已保留。",
            )
        }
        return result
    }

    /**
     * 摘要输入预算内从最旧端截断（保留最近段）；至少保留一条消息，
     * 单条消息本身超预算时仍原样送入（无法再细分）。
     */
    private fun boundSummaryInput(
        safe: List<AgentModelClient.ConversationMessage>,
        budgetChars: Int,
    ): List<AgentModelClient.ConversationMessage> {
        if (safe.isEmpty()) return safe
        var used = 0
        var keepFrom = safe.size
        for (index in safe.indices.reversed()) {
            if (keepFrom < safe.size && used + summaryInputCost(safe[index]) > budgetChars) break
            used += summaryInputCost(safe[index])
            keepFrom = index
        }
        return safe.drop(keepFrom)
    }

    private fun summaryInputCost(message: AgentModelClient.ConversationMessage): Int =
        AgentConversationCodec.toJsonObject(message).toString().length + 1

    /**
     * 压缩边界 `end`：`history.drop(end)` 是逐字保留的近期尾巴，`history.take(end)` 进入摘要。
     *
     * 与旧行为（尽量少保留）相反，这里按 [AgentContextBudget.RETAIN_RATIO] 的 token 预算
     * 从末尾往前保留——压缩"少而狠"才能减少压缩次数、摘要漂移与缓存击穿。
     */
    private fun compactEnd(history: List<JSONObject>): Int? {
        val retain = AgentContextBudget.retainTokens(config.contextWindow)
        if (retain == null) return (history.size downTo 1).firstOrNull { canSplit(history, it) }
        var used = 0.0
        var from = history.size
        while (from > 1) {
            // P0-4 修复（2026-10-16，见 docs/reviews/context-compaction-audit.md）：
            // 不再乘 calibration。原实现借用「整体校准值」换算成本，但该值反映的是全文平均
            // token 密度；而压缩发生时 calibration 往往仍是初值 0.4（来自「估算高估 4.82 倍」
            // 的时代），与当前「低估」的实际方向相反 → cost 被缩小 2.5 倍 → 保留量超预算约 1.8 倍
            // （真机实测：预算允许 256 条，实际保留 454 条 / 634K 字符 / 真实 ~350K tokens）。
            // 保留区内容同质（工具结果为主），用未校准的 raw 估算做相对比较即可。
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
            if (!message.optBoolean("_eta_context_summary")) return@forEach
            val found = ANCHOR_BLOCK.find(message.optString("content"))?.groupValues?.get(1)?.trim()
            if (!found.isNullOrBlank()) return found
        }
        return history.firstOrNull {
            it.optString("role") == "user" && !it.has("_eta_observation") && !it.optBoolean("_eta_context_summary")
        }?.optString("content")?.trim()?.takeIf { it.isNotBlank() }
    }

    companion object {
        /** 压缩后至少要比压缩前小这个百分比，才值得提交（否则视为无效压缩）。 */
        const val MIN_REDUCTION_PERCENT = 80

        /** R4：摘要输入预算（字符）。按窗口一半，夹在 32k..512k；窗口未知按 128k 基准。 */
        fun summaryInputBudgetChars(window: Int?): Int {
            val resolved = window?.takeIf { it > 0 } ?: DEFAULT_SUMMARY_INPUT_WINDOW
            return (resolved / 2).coerceIn(MIN_SUMMARY_INPUT_CHARS, MAX_SUMMARY_INPUT_CHARS)
        }

        private const val DEFAULT_SUMMARY_INPUT_WINDOW = 128_000
        private const val MIN_SUMMARY_INPUT_CHARS = 32_000
        private const val MAX_SUMMARY_INPUT_CHARS = 512_000

        /** 原样保留、不参与重写的检查点代数（1 = 只保最近一代，更早的合并进新摘要）。 */
        const val KEEP_RECENT_CHECKPOINTS = 1

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
