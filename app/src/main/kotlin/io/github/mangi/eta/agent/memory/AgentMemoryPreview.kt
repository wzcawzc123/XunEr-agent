package io.github.mangi.eta.agent.memory

/**
 * 记忆文件在**预览模式**下该渲染哪一段。
 *
 * ## 为什么需要
 *
 * 记忆页原本把整个 `MEMORY.md` 塞进一个可滚动 Text。文件大的时候 Compose 必须
 * 全量测量布局——真机实测 242KB / 17 万字符时进页面白屏半秒以上，切换预览/编辑同样慢。
 *
 * 但预览真正需要的是**核心区**：那是每轮实际注入模型的部分（受 `窗口/16` 预算约束），
 * 也是用户最常看的地方。归档区是历史沉淀，按需进编辑模式或搜索查看即可。
 *
 * ## 口径必须与注入侧一致
 *
 * 切片规则与 [AgentMemoryContextBuilder] 完全对齐，否则会出现「模型看到的」和
 * 「用户预览到的」不是同一段——那种不一致没有任何报错，只会让人怀疑记忆没生效：
 *
 * - 核心段判定：整行 `trim()` 后等于 [MemoryText.CORE_HEADING]；
 * - 段边界：下一个一级标题（`# ` 开头，含 `##` 不算）之前；
 * - **多个同名核心段全部渲染**：注入侧是合并注入的，预览若只显示第一个，
 *   用户会以为第二段内容丢了；
 * - 找不到核心段时不猜结构，原样返回全文（旧文件、自定义结构都安全降级）。
 */
internal object AgentMemoryPreview {

    data class Slice(
        /** 预览要渲染的文本。 */
        val text: String,
        /** 未渲染部分的字符数（0 表示整份文件都显示了）。 */
        val archivedChars: Int,
        /** 是否真的做了截断。 */
        val truncated: Boolean,
        /** 命中的核心段个数（>1 说明文件里有历史遗留的重复标题）。 */
        val coreSectionCount: Int,
    )

    /** 多个核心段之间的分隔提示（历史遗留重复标题时才会出现）。 */
    private const val SECTION_DIVIDER = "…（以下是另一个同名「# 核心记忆」段，建议合并成一节）"

    fun slice(content: String): Slice {
        if (content.isBlank()) return Slice(content, 0, false, 0)
        val lines = content.lines()
        val ranges = coreRanges(lines)
        // 没有核心段标记：不猜结构，原样返回
        if (ranges.isEmpty()) return Slice(content, 0, false, 0)

        val kept = BooleanArray(lines.size)
        for (range in ranges) {
            for (index in range) kept[index] = true
        }
        var archivedChars = 0
        lines.forEachIndexed { index, line ->
            if (!kept[index]) archivedChars += line.length + 1
        }
        // 核心段覆盖全文（没有归档区）：没必要拼接，原样返回
        if (archivedChars == 0) return Slice(content, 0, false, ranges.size)

        val text = buildString {
            ranges.forEachIndexed { order, range ->
                if (order > 0) {
                    append('\n')
                    append(SECTION_DIVIDER)
                    append('\n')
                }
                for (index in range) {
                    if (isNotEmpty()) append('\n')
                    append(lines[index])
                }
            }
        }
        return Slice(text, archivedChars, true, ranges.size)
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
