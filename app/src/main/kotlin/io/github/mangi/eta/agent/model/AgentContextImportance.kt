package io.github.mangi.eta.agent.model

/**
 * 上下文重要性启发式（W2，context-compaction-v2）。
 *
 * 规则而非 ML：可解释、可测、零依赖。三处共用：
 * - [AgentToolResultSlimmer]：关键行筛选；
 * - [AgentHistoryTrimmer]：裁剪备忘的内容选取；
 * - [AgentContextSummarizer]：摘要后的关键事实自检。
 */
internal object AgentContextImportance {

    private val PATH_OR_URL = Regex("""(/[\w.\-]+)+|https?://\S+|[A-Za-z]:\\[^\s]+""")
    private val LONG_ID = Regex("""\b[0-9a-fA-F]{8,}\b""")
    private val CONSTRAINT = Regex(
        """必须|不要|禁止|记住|不得|务必|只能|不能|约束|优先|别忘|don't|never|always|must|remember""",
        RegexOption.IGNORE_CASE,
    )
    private val METRIC = Regex("""\d+[KkMmGg]?B\b|\d+\s*(个|次|条|项|步|轮)""")

    /** 是否含高价值信息（路径/URL/长 ID/数量指标/错误/约束）。 */
    fun isValuable(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        return PATH_OR_URL.containsMatchIn(t) ||
            LONG_ID.containsMatchIn(t) ||
            METRIC.containsMatchIn(t) ||
            CONSTRAINT.containsMatchIn(t) ||
            t.contains("错误") || t.contains("失败") || t.contains("Error", ignoreCase = true)
    }

    /** 是否是约束句（用户的强制要求）。 */
    fun isConstraint(text: String): Boolean = CONSTRAINT.containsMatchIn(text)

    /**
     * 从历史里提取关键事实候选（用于摘要内容自检）。
     *
     * 优先级：约束句 > 路径/URL > 长 ID；同一事实去重；上限 [limit] 条防止提示过长。
     */
    fun extractKeyFacts(messages: List<AgentModelClient.ConversationMessage>, limit: Int = 12): List<String> {
        val constraints = LinkedHashSet<String>()
        val paths = LinkedHashSet<String>()
        val ids = LinkedHashSet<String>()
        for (message in messages) {
            if (message.role != "user" && message.role != "assistant") continue
            for (rawLine in message.content.lines()) {
                val line = rawLine.trim()
                if (line.isEmpty() || line.length > 300) continue
                when {
                    isConstraint(line) -> constraints.add(line.take(160))
                    PATH_OR_URL.containsMatchIn(line) -> paths.add(line.take(160))
                    LONG_ID.containsMatchIn(line) -> ids.add(line.take(160))
                }
            }
        }
        return (constraints + paths + ids).take(limit)
    }

    /** 摘要里是否覆盖了给定事实（子串包含，宽松匹配）。 */
    fun coversFact(summary: String, fact: String): Boolean {
        val needle = fact.trim()
        if (needle.isEmpty()) return true
        if (summary.contains(needle)) return true
        // 宽松：长句取前 12 字符再查（覆盖"摘要复述了约束但补了措辞"）
        val head = needle.take(12)
        return head.length >= 8 && summary.contains(head)
    }
}
