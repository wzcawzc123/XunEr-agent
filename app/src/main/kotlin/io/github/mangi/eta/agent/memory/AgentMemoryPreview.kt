package io.github.mangi.eta.agent.memory

/**
 * 记忆文件在**预览模式**下该渲染哪一段。
 *
 * ## 为什么需要
 *
 * 记忆页把整个 MEMORY.md（真实大小 242KB / 17 万字符）塞进一个可滚动 Text，
 * Compose 必须全量测量布局 —— 进入页面白屏半秒以上、切换长期/短期同样慢。
 * 这是上游既有实现（`fab1fea` 2026-08-03），但上游没有这么大的记忆文件。
 *
 * 预览只需要看**核心区**（每轮真正注入模型的那部分，默认 32K 预算）。
 * 归档区按需进编辑模式查看，那里本来就是主动操作、可以接受慢。
 *
 * ## 切分规则
 *
 * - 从 `# 核心记忆` 开始；
 * - 到**下一个一级标题**（`# `）之前结束；
 * - 找不到 `# 核心记忆` 时返回全文（旧文件、用户自定义结构都安全降级）。
 */
internal object AgentMemoryPreview {

    data class Slice(
        /** 预览要渲染的文本。 */
        val text: String,
        /** 核心区之后还剩多少字符（0 表示没有归档区）。 */
        val archivedChars: Int,
        /** 是否真的做了截断。 */
        val truncated: Boolean,
    )

    fun slice(content: String): Slice {
        if (content.isBlank()) return Slice(content, 0, false)

        val lines = content.lines()
        val coreStart = lines.indexOfFirst { it.startsWith(MemoryText.CORE_HEADING) }
        // 没有核心区标记：不猜结构，原样返回
        if (coreStart < 0) return Slice(content, 0, false)

        var coreEnd = lines.size
        for (index in coreStart + 1 until lines.size) {
            // 一级标题（`# ` 开头，且不是 `##`）视为核心区结束
            val line = lines[index]
            if (line.startsWith("# ") && !line.startsWith("##")) {
                coreEnd = index
                break
            }
        }

        if (coreEnd >= lines.size) {
            // 核心区就是全文
            return Slice(content, 0, false)
        }

        val core = lines.subList(coreStart, coreEnd).joinToString("\n")
        val archived = lines.subList(coreEnd, lines.size).joinToString("\n")
        return Slice(core, archived.length, true)
    }

    /** 大纲：收集 1-3 级标题（与注入索引/查重同一口径），返回去重后的标题行（含 `#` 前缀）。 */
    fun headings(content: String): List<String> =
        content.lineSequence()
            .map { it.trimEnd() }
            .filter { OUTLINE_HEADING.matches(it) }
            .distinct()
            .toList()

    /**
     * 按标题取整节文本（大纲直达用）：匹配忽略大小写与 `#` 个数差异，取首个命中；
     * 节的终点是下一个**同级或更高级**标题。找不到返回 null。
     */
    fun section(content: String, rawHeading: String): String? {
        val lines = content.lines()
        val wanted = headingTitle(rawHeading)
        if (wanted.isEmpty()) return null
        val start = lines.indexOfFirst {
            OUTLINE_HEADING.matches(it.trimEnd()) && headingTitle(it) == wanted
        }
        if (start < 0) return null
        val level = headingLevel(lines[start])
        var end = lines.size
        for (index in start + 1 until lines.size) {
            val lineLevel = headingLevel(lines[index])
            if (lineLevel in 1..level) {
                end = index
                break
            }
        }
        return lines.subList(start, end).joinToString("\n")
    }

    private val OUTLINE_HEADING = Regex("^#{1,3}\\s+.+$")

    private fun headingLevel(line: String): Int {
        val trimmed = line.trimStart()
        if (!trimmed.startsWith("#")) return 0
        val hashes = trimmed.takeWhile { it == '#' }.length
        if (hashes !in 1..6) return 0
        if (trimmed.length <= hashes || trimmed[hashes] != ' ') return 0
        return hashes
    }

    private fun headingTitle(line: String): String =
        line.trimStart().trimStart('#').trim()
}
