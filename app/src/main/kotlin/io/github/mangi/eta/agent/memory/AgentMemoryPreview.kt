package io.github.mangi.eta.agent.memory

/**
 * 核心区字符统计，供记忆页的「核心区水位」展示使用。
 *
 * ## 为什么只算核心区
 *
 * 每轮真正注入模型的是 `# 核心记忆` 那几段，受 `窗口/16` 预算约束；归档区不进上下文。
 * 用户编辑草稿时若能实时看到核心区占了多少预算，就不必等保存后再去问模型「为什么没记住」。
 *
 * ## 口径必须与注入侧一致
 *
 * 注入侧把核心行用 `\n` **连接**起来（行间一个换行，段间也只有一个），不是每行末尾都补 `\n`，
 * 所以这里是「行长度之和 + 行数 - 1」。用别的算法会得出与模型实际占用不同的数字——
 * 那种不一致没有任何报错，只会让人怀疑记忆没生效。
 */
internal object AgentMemoryPreview {

    /**
     * 核心区总字符数（与注入侧累加口径逐字符一致）。
     *
     * 用于「核心区水位」展示：用户编辑草稿时能实时看到会不会超预算，
     * 不必等保存后再去问模型为什么没记住东西。
     */
    fun coreChars(content: String): Int {
        if (content.isBlank()) return 0
        val lines = content.lines()
        var chars = 0
        var lineCount = 0
        for (range in coreRanges(lines)) {
            for (index in range) {
                chars += lines[index].length
                lineCount++
            }
        }
        return if (lineCount == 0) 0 else chars + lineCount - 1
    }

    /**
     * 所有核心段的行下标区间（0 起、闭区间）。
     *
     * 与注入侧同一口径：整行 trim 后等于 [MemoryText.CORE_HEADING]，段终点是下一个一级标题。
     */
    private fun coreRanges(lines: List<String>): List<IntRange> = buildList {
        var index = 0
        while (index < lines.size) {
            if (lines[index].trim() != MemoryText.CORE_HEADING) {
                index++
                continue
            }
            var end = index + 1
            while (end < lines.size && !lines[end].startsWith("# ")) end++
            add(index until end)
            index = end
        }
    }
}
