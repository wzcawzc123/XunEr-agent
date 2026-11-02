package io.github.mangi.eta.agent.memory

import io.github.mangi.eta.data.repository.AgentMemorySnapshot

internal data class AgentMemoryContext(
    val enabled: Boolean,
    val revision: String,
    val byteSize: Int,
    val coreContent: String,
    val coreTruncated: Boolean,
    val headingIndex: String,
    val coreBudgetChars: Int,
    /** 识别到的核心记忆段数：同名 `# 核心记忆` 可能有多段，全部合并注入。 */
    val coreSectionCount: Int = 0,
    /** 实际注入的核心区行数。 */
    val coreInjectedLines: Int = 0,
    /** 核心区总行数（注入 + 未注入）。 */
    val coreTotalLines: Int = 0,
    /** 被截断时，下一次 `memory_get(start_line=…)` 应从这一行继续（文件绝对行号，1 起）。 */
    val coreNextLine: Int? = null,
    /** 标题索引的告警（索引被截断、核心段重复）。 */
    val headingWarning: String = "",
) {
    companion object {
        val DISABLED = AgentMemoryContext(
            enabled = false,
            revision = "",
            byteSize = 0,
            coreContent = "",
            coreTruncated = false,
            headingIndex = "",
            coreBudgetChars = 0,
        )
    }
}

internal object AgentMemoryContextBuilder {
    fun empty(contextWindow: Int?): AgentMemoryContext = build(
        snapshot = AgentMemorySnapshot(
            content = "",
            revision = EMPTY_SHA256,
            byteSize = 0,
            lineCount = 0,
        ),
        contextWindow = contextWindow,
    )

    fun build(
        snapshot: AgentMemorySnapshot,
        contextWindow: Int?,
    ): AgentMemoryContext {
        val coreBudget = coreBudgetChars(contextWindow)
        val core = extractCoreSections(snapshot.content)
        val slice = truncateCore(core, coreBudget)
        val index = buildHeadingIndex(snapshot.content)
        return AgentMemoryContext(
            enabled = true,
            revision = snapshot.revision,
            byteSize = snapshot.byteSize,
            coreContent = slice.text,
            coreTruncated = slice.truncated,
            headingIndex = index.text,
            coreBudgetChars = coreBudget,
            coreSectionCount = core.sectionCount,
            coreInjectedLines = slice.injectedLines,
            coreTotalLines = slice.totalLines,
            coreNextLine = slice.nextLine,
            headingWarning = index.warning,
        )
    }

    fun coreBudgetChars(contextWindow: Int?): Int {
        val resolvedWindow = contextWindow?.takeIf { it > 0 } ?: DEFAULT_CONTEXT_WINDOW
        return (resolvedWindow / CONTEXT_WINDOW_DIVISOR)
            .coerceIn(MIN_CORE_CHARS, MAX_CORE_CHARS)
    }

    /**
     * 收集**所有** `# 核心记忆` 段。
     *
     * 早期实现只取第一段：文件里若已存在两个同名段（旧版本允许 append 重复标题），
     * 第二段会被整段静默排除在注入之外 —— 内容看起来还在文件里，模型永远看不到。
     * 写入侧现在会拒绝新造重复，但**历史遗留的重复必须能读出来**，所以这里全收。
     */
    private fun extractCoreSections(content: String): CoreExtraction {
        if (content.isEmpty()) return CoreExtraction("", emptyList(), 0)
        val lines = content.split('\n')
        val text = StringBuilder()
        val fileLines = mutableListOf<Int>()
        var sectionCount = 0
        var index = 0
        while (index < lines.size) {
            if (lines[index].trim() != MemoryText.CORE_HEADING) {
                index++
                continue
            }
            sectionCount++
            // 一段的边界：标题行 → 下一个一级标题之前。
            var end = index + 1
            while (end < lines.size && !lines[end].startsWith("# ")) end++
            for (cursor in index until end) {
                if (text.isNotEmpty()) text.append('\n')
                text.append(lines[cursor])
                fileLines += cursor + 1
            }
            index = end
        }
        return CoreExtraction(text.toString(), fileLines, sectionCount)
    }

    /**
     * 按**行边界**截断，而不是直接 `take(budget)`。
     *
     * 行内截断会让模型看到半句话，且可能切断代理对；按行截断虽然会少用一点预算，
     * 但保证注入的每一行都是完整的，并留下"下一行从哪开始"的锚点。
     */
    private fun truncateCore(core: CoreExtraction, budget: Int): CoreSlice {
        val lines = if (core.text.isEmpty()) emptyList() else core.text.split('\n')
        if (core.text.length <= budget) {
            return CoreSlice(core.text, false, lines.size, lines.size, null)
        }
        val kept = StringBuilder()
        var cursor = 0
        while (cursor < lines.size) {
            val separator = if (kept.isEmpty()) 0 else 1
            if (kept.length + separator + lines[cursor].length > budget) {
                // 单行就吃掉剩余预算：截断这一行（保护代理对）后停手。
                val remaining = budget - kept.length - separator
                if (remaining > 0) {
                    if (kept.isNotEmpty()) kept.append('\n')
                    kept.append(MemoryText.safeTake(lines[cursor], remaining))
                    cursor++
                }
                break
            }
            if (kept.isNotEmpty()) kept.append('\n')
            kept.append(lines[cursor])
            cursor++
        }
        return CoreSlice(
            text = kept.toString(),
            truncated = true,
            injectedLines = cursor,
            totalLines = lines.size,
            nextLine = core.fileLines.getOrNull(cursor),
        )
    }

    /** 标题索引：按行边界取，超限时明确告知"只显示了前 N 个"，而不是静默腰斩。 */
    private fun buildHeadingIndex(content: String): HeadingIndex {
        if (content.isEmpty()) return HeadingIndex("", "")
        val headings = content.lineSequence()
            .map { it.trimEnd() }
            .filter { HEADING.matches(it) }
            .toList()
        if (headings.isEmpty()) return HeadingIndex("", "")
        val builder = StringBuilder()
        var shown = 0
        for (heading in headings) {
            val separator = if (builder.isEmpty()) 0 else 1
            if (builder.length + separator + heading.length > MAX_HEADING_INDEX_CHARS) break
            if (builder.isNotEmpty()) builder.append('\n')
            builder.append(heading)
            shown++
        }
        val warnings = buildList {
            if (shown < headings.size) {
                add("标题索引仅列出前 $shown / ${headings.size} 个标题，其余请用 memory_get 翻页查看")
            }
            val coreSections = headings.count { it.trim() == MemoryText.CORE_HEADING }
            if (coreSections > 1) {
                add("检测到 $coreSections 个同名「${MemoryText.CORE_HEADING}」段，已全部合并注入；建议合并成一节以免混淆")
            }
        }
        return HeadingIndex(builder.toString(), warnings.joinToString("；"))
    }

    private class CoreExtraction(
        val text: String,
        /** 合并后每一行对应的文件绝对行号（1 起）。 */
        val fileLines: List<Int>,
        val sectionCount: Int,
    )

    private class CoreSlice(
        val text: String,
        val truncated: Boolean,
        val injectedLines: Int,
        val totalLines: Int,
        val nextLine: Int?,
    )

    private class HeadingIndex(val text: String, val warning: String)

    private val HEADING = Regex("^#{1,2}\\s+.+$")
    /** 窗口未知时用于按比例分配记忆、世界书等注入预算的保守基准，不参与压缩判定。 */
    const val DEFAULT_CONTEXT_WINDOW = 128_000
    private const val CONTEXT_WINDOW_DIVISOR = 16
    private const val MIN_CORE_CHARS = 4_000
    private const val MAX_CORE_CHARS = 32_000
    private const val MAX_HEADING_INDEX_CHARS = 4_000
    private const val EMPTY_SHA256 =
        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
}
