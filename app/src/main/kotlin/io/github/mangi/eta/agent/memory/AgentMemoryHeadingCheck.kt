package io.github.mangi.eta.agent.memory

/**
 * 同名标题检测。
 *
 * 为什么只做**标题级**去重，不做内容相似度：
 * - 标题重复是**确定性的**，不会误判；内容相似度需要阈值，阈值设多少都可能错杀；
 * - 真机 MEMORY.md 里 `## 待办` 出现了 3 次、`## 状态` 出现多次 ——
 *   这类重复会让 `memory_get(section=...)` 命中哪一段变得不可预测（实现里是"取第一个匹配"）。
 *
 * 只**提示**不拦截：写入同名小节往往是"用户想更新这一节"，用 `replace_section` 才是对的；
 * 而 `append` 追加同名标题几乎总是意外。检测结果用于告诉模型"你可能想改成 replace"。
 */
internal object AgentMemoryHeadingCheck {

    /** Markdown 一到三级标题；与 `AgentMemoryRepository` 的索引口径保持一致。 */
    private val HEADING = Regex("""^#{1,3}\s+(.+?)\s*$""")

    data class Result(
        /** 这次写入的内容里出现的标题。 */
        val incoming: List<String>,
        /** 与文档中已有标题重名者（去重后）。 */
        val duplicates: List<String>,
    ) {
        val hasDuplicates: Boolean get() = duplicates.isNotEmpty()
    }

    /**
     * 比较即将写入的 [incomingContent] 与文档现有 [existingContent]。
     *
     * @param mode 写入模式。`replace_section` 本身就是"替换已有小节"，其 section 参数
     *   计入重名属于正常行为，因此该模式下**不考虑被替换那一节**的正常性 —— 但我们不做
     *   复杂豁免，只把重名事实报出来，由调用方决定是否展示。
     */
    fun check(existingContent: String, incomingContent: String): Result {
        val incoming = headings(incomingContent)
        if (incoming.isEmpty()) return Result(emptyList(), emptyList())
        val existing = headings(existingContent).toSet()
        val duplicates = incoming.distinct().filter { it in existing }
        return Result(incoming.distinct(), duplicates)
    }

    /** 提取标题文本（去掉 `#` 与首尾空白），保持出现顺序。 */
    fun headings(content: String): List<String> = content.lineSequence()
        .mapNotNull { line -> HEADING.matchEntire(line.trim())?.groupValues?.get(1)?.trim() }
        .filter { it.isNotEmpty() }
        .toList()
}
