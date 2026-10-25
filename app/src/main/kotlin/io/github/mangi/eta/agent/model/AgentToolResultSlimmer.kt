package io.github.mangi.eta.agent.model

import org.json.JSONArray

/**
 * 摘要入口的工具结果瘦身器（W1，context-compaction-v2）。
 *
 * 动机：工具结果（终端输出、读文件、抓网页）是上下文里最大的 token 来源。
 * 摘要输入预算（[AgentContextCompactor.boundSummaryInput]）超限从最旧端丢弃，
 * 模型只能看到"被截断的片段"而不是全貌——瘦身后同等预算能覆盖更多轮次，
 * 摘要质量随之提升；结论/关键行保留，细节丢弃。
 *
 * 与 [AgentToolResultPruner] 的分工：
 * - Pruner 作用于**出站请求**（每轮都跑，保留头尾中段标记）；
 * - Slimmer 只作用于**摘要输入**（压缩时一次），做结构性瘦身，不修改持久历史。
 *
 * 约束：
 * - 近期轮（默认 2 个用户轮）的工具结果原样保留——它们常被后续引用；
 * - 瘦形只改 content，`role/toolCallId/messageId/_eta_*` 语义不变，链接不断；
 * - `compactEnd` 的批次边界判定基于 JSON 消息结构，本瘦形发生在 transcript 之后、
 *   不回写 messages 数组，因此不影响边界判定。
 */
internal object AgentToolResultSlimmer {

    /** 近期保留的用户轮数（不含本轮）。 */
    const val KEEP_RECENT_TURNS = 2

    /** 超过该字符数才瘦身；小结果原样保留（瘦了反而丢细节）。 */
    const val SLIM_THRESHOLD_CHARS = 2_048

    /** 结论取前多少个非空行。 */
    const val CONCLUSION_LINES = 2

    /** 单条关键行的长度上限。 */
    const val KEY_LINE_MAX_CHARS = 200

    /** 关键行数量上限。 */
    const val KEY_LINE_MAX_COUNT = 8

    const val MARKER = "工具结果已瘦身"

    /**
     * 对摘要输入做工具结果瘦身。
     *
     * @param messages 已展开为文本的转写消息（[AgentContextCompactor] 的 safe 列表）
     * @param keepRecentTurns 近期保留的用户轮数
     * @return 瘦身后的列表；无改动时返回**原引用**（保持前缀稳定、便于测试断言）
     */
    fun slim(
        messages: List<AgentModelClient.ConversationMessage>,
        keepRecentTurns: Int = KEEP_RECENT_TURNS,
    ): List<AgentModelClient.ConversationMessage> {
        if (messages.isEmpty()) return messages
        val cutoff = cutoffIndex(messages, keepRecentTurns)
        if (cutoff <= 0) return messages
        val toolNames = indexToolNames(messages)
        var changed = false
        val out = messages.mapIndexed { index, message ->
            if (index >= cutoff ||
                message.role != ROLE_TOOL ||
                message.content.length <= SLIM_THRESHOLD_CHARS
            ) {
                message
            } else {
                changed = true
                message.copy(content = slimContent(message.content, toolNames[message.toolCallId]))
            }
        }
        return if (changed) out else messages
    }

    /**
     * 瘦形截止点：从尾部数第 [keepRecentTurns] 个用户消息的起始下标。
     * 用户消息不足时返回 0（全部保留，宁可不瘦也不误伤近期）。
     */
    private fun cutoffIndex(messages: List<AgentModelClient.ConversationMessage>, keepRecentTurns: Int): Int {
        if (keepRecentTurns <= 0) return 0
        var seen = 0
        for (index in messages.indices.reversed()) {
            val message = messages[index]
            if (message.role == "user" && !message.contextSummary) {
                seen++
                if (seen == keepRecentTurns) return index
            }
        }
        return 0
    }

    /** 从 assistant 的 toolCallsJson 反查 toolCallId → 工具名（尽力而为，查不到为空）。 */
    private fun indexToolNames(messages: List<AgentModelClient.ConversationMessage>): Map<String, String> {
        val names = mutableMapOf<String, String>()
        for (message in messages) {
            if (message.role != "assistant" || message.toolCallsJson.isBlank()) continue
            runCatching {
                val calls = JSONArray(message.toolCallsJson)
                for (index in 0 until calls.length()) {
                    val call = calls.optJSONObject(index) ?: continue
                    val id = call.optString("id")
                    val name = call.optJSONObject("function")?.optString("name")
                        ?.takeIf { it.isNotBlank() }
                        ?: call.optString("name").takeIf { it.isNotBlank() }
                        ?: continue
                    if (id.isNotBlank()) names[id] = name
                }
            }
        }
        return names
    }

    /**
     * 把一段工具结果瘦形成「结论 + 关键行 + 原长」。
     *
     * JSON 结果先解析出 ok/code 再取文本；纯文本直接取行。失败结果优先保留错误码与错误信息——
     * 摘要需要知道"什么没做成"，这比成功细节更重要。
     */
    internal fun slimContent(raw: String, toolName: String?, originalChars: Int = raw.length): String {
        val lines = raw.lines()
        val (status, strippedLines) = extractStatus(raw, lines)
        val nonEmpty = strippedLines.filter { it.isNotBlank() }
        val conclusion = nonEmpty.take(CONCLUSION_LINES).map { it.trim().take(KEY_LINE_MAX_CHARS) }
        val keyLines = nonEmpty.asSequence()
            .filter { looksValuable(it) }
            .map { it.trim().take(KEY_LINE_MAX_CHARS) }
            .distinct()
            .take(KEY_LINE_MAX_COUNT)
            .toList()
        return buildString {
            append('[').append(MARKER)
            if (!toolName.isNullOrBlank()) append(" tool=").append(toolName)
            status?.let { append(' ').append(it) }
            append(" 原长=").append(originalChars).append(" 字符]\n")
            if (conclusion.isNotEmpty()) {
                append("结论：\n")
                conclusion.forEach { append("- ").append(it).append('\n') }
            }
            if (keyLines.isNotEmpty()) {
                append("关键行：\n")
                keyLines.forEach { append("- ").append(it).append('\n') }
            }
        }.trimEnd()
    }

    /** 从 JSON 工具结果提取成败状态；解析失败时退化为正则探测；纯文本返回 null。 */
    private fun extractStatus(raw: String, lines: List<String>): Pair<String?, List<String>> {
        val trimmed = raw.trim()
        if (trimmed.startsWith("{")) {
            runCatching {
                val obj = org.json.JSONObject(trimmed)
                val ok = if (obj.has("ok")) obj.optBoolean("ok", true) else null
                val code = obj.optString("code").takeIf { it.isNotBlank() }
                val status = when {
                    ok == false -> "失败" + (code?.let { " code=$it" } ?: "")
                    ok == true -> "成功"
                    else -> null
                }
                // JSON 结果的"行"即解析出的可读字段，用原始行兜底
                return status to lines
            }
            // 非法 JSON（截断/嵌套异常）也尽量把成败与错误码找出来：摘要最需要"什么没做成"。
            val okFalse = Regex(""""ok"\s*:\s*false""").containsMatchIn(trimmed)
            val code = Regex(""""code"\s*:\s*"([^"]+)"""").find(trimmed)?.groupValues?.get(1)
            if (okFalse || code != null) {
                val status = "失败" + (code?.let { " code=$it" } ?: "")
                return status to lines
            }
        }
        return null to lines
    }

    /** 是否像关键行：路径/URL/ID/数量指标/错误词。 */
    private fun looksValuable(line: String): Boolean {
        val text = line.trim()
        if (text.isEmpty()) return false
        return text.contains('/') ||
            text.contains("http", ignoreCase = true) ||
            text.contains('\\') ||
            text.contains(Regex("\\b[0-9a-fA-F]{8,}\\b")) ||
            text.contains(Regex("\\d+[KkMmGg]?B\\b|\\d+\\s*(个|次|条|项|步)")) ||
            text.contains("错误") || text.contains("失败") || text.contains("Error", ignoreCase = true) ||
            text.contains("必须") || text.contains("不要") || text.contains("禁止")
    }

    private const val ROLE_TOOL = "tool"
}
