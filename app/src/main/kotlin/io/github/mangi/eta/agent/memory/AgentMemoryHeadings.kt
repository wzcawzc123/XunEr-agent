package io.github.mangi.eta.agent.memory

/**
 * Markdown 标题的解析与区间计算 —— 记忆按标题寻址的唯一口径。
 *
 * ## 为什么要统一到这一处
 *
 * 记忆写入有三条路径（按标题替换、同名标题检测、核心区提取），三处如果各自写正则，
 * 口径一旦漂移就会**静默出错**：检测认为不重名、实际写入后却多了一个 `# 核心记忆`，
 * 而注入侧只取第一个 —— 内容就此消失，没有任何报错。
 *
 * ## 区间规则
 *
 * 一节的范围是「标题行 → 下一个**同级或更高级**标题之前」：
 * `## 待办` 的子节 `### 今天` 属于它，但下一个 `## 状态` 不属于。
 * 这与 Markdown 的渲染语义一致，也是用户对「这一节」的直觉。
 */
internal object AgentMemoryHeadings {
    /** 一到三级标题；四级及以下视为正文细节，不参与寻址。 */
    private val HEADING = Regex("""^(#{1,3})\s+(.+?)\s*$""")

    data class Heading(
        /** 行号，1 起。 */
        val line: Int,
        /** `#` 个数。 */
        val level: Int,
        /** 去掉 `#` 与首尾空白的标题文本。 */
        val title: String,
    ) {
        /** 带 `#` 的完整标题行，便于回显给模型确认。 */
        val rendered: String get() = "#".repeat(level) + " " + title
    }

    /** 按出现顺序列出所有标题。 */
    fun parse(lines: List<String>): List<Heading> = buildList {
        lines.forEachIndexed { index, line ->
            val match = HEADING.matchEntire(line.trimEnd()) ?: return@forEachIndexed
            val marks = match.groupValues[1]
            val title = match.groupValues[2]
            if (title.isNotEmpty()) add(Heading(index + 1, marks.length, title))
        }
    }

    /**
     * 某节的行区间（1 起、闭区间）。
     *
     * @param index 在 [parse] 结果里的下标
     * @param lineCount 全文行数
     */
    fun sectionRange(all: List<Heading>, index: Int, lineCount: Int): IntRange {
        val heading = all[index]
        val next = all.drop(index + 1).firstOrNull { it.level <= heading.level }
        val end = if (next == null) lineCount else next.line - 1
        return heading.line..end
    }

    /** 标题匹配（忽略大小写与前导 `#`），返回所有命中的下标。 */
    fun matchIndices(all: List<Heading>, section: String): List<Int> {
        val needle = section.trim().trimStart('#').trim()
        if (needle.isEmpty()) return emptyList()
        return all.indices.filter { all[it].title.equals(needle, ignoreCase = true) }
    }

    /** 可用标题清单（给报错用的候选），最多 [limit] 条，超出用省略号收尾。 */
    fun catalogue(all: List<Heading>, limit: Int = 40): String {
        if (all.isEmpty()) return "（没有任何标题）"
        val shown = all.take(limit).joinToString("\n") { "  第 ${it.line} 行  ${it.rendered}" }
        return if (all.size > limit) "$shown\n  …共 ${all.size} 个标题，请用 memory_get 翻页查看其余" else shown
    }
}
