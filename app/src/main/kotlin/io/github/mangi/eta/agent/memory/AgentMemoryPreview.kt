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

    private const val CORE_HEADING = "# 核心记忆"

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
        val coreStart = lines.indexOfFirst { it.startsWith(CORE_HEADING) }
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
}
