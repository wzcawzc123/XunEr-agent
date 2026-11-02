package io.github.mangi.eta.agent.memory

/**
 * 记忆全文搜索（大小写不敏感、按行返回）。
 *
 * ## 为什么按行返回而不是过滤原文
 *
 * 早先的做法是把命中行拼回一段 Markdown 再去渲染，问题有两个：
 * 1. 命中行脱离上下文后，`#`/列表/代码块的结构被切断，渲染结果可能和原文完全不同；
 * 2. 高亮只能靠往原文插 `**`，一旦命中词落在代码块或链接里就会破坏语法
 *    （所以那时干脆「查询含特殊字符就不高亮」）。
 *
 * 现在返回结构化命中（行号 + 原文 + 高亮区间），UI 用纯文本逐行渲染 + SpanStyle 高亮：
 * 不碰 Markdown，也不会因为查询里有 `*` `_` 就失去高亮。行号还能让用户切到编辑后定位。
 *
 * ## 上限
 *
 * 大文件里搜「的」这类高频词可能命中上万行。渲染上限只影响展示，[Result.total]
 * 始终是真实命中次数——用户看到的数字不该被展示上限影响。
 */
internal object AgentMemorySearch {

    /** 单行内的一次命中区间（0 起、半开）。 */
    data class Hit(val line: Int, val text: String, val ranges: List<IntRange>)

    data class Result(
        /** 展示用的命中行（最多 limit 行）。 */
        val hits: List<Hit>,
        /** 命中次数（同一行多处命中分别计数）。 */
        val total: Int,
        /** 命中行数（判断展示是否被截断只能用行数，不能用次数）。 */
        val matchedLines: Int,
        /** 全文总行数，用于提示「在第 N 行」。 */
        val totalLines: Int,
    ) {
        val isEmpty: Boolean get() = total == 0

        /** 展示是否被上限截断（UI 据此提示「只显示前 N 行」）。 */
        val truncated: Boolean get() = hits.size < matchedLines
    }

    const val DEFAULT_LIMIT = 200

    fun search(content: String, query: String, limit: Int = DEFAULT_LIMIT): Result {
        val needle = query.trim()
        if (needle.isEmpty() || content.isEmpty()) return Result(emptyList(), 0, 0, 0)
        val hits = ArrayList<Hit>()
        var total = 0
        var matchedLines = 0
        var lineNumber = 0
        for (line in content.lineSequence()) {
            lineNumber++
            val ranges = rangesIn(line, needle)
            if (ranges.isEmpty()) continue
            total += ranges.size
            matchedLines++
            if (hits.size < limit) hits.add(Hit(lineNumber, line, ranges))
        }
        return Result(hits, total, matchedLines, lineNumber)
    }

    /** 单行内所有不重叠命中（大小写不敏感）。 */
    private fun rangesIn(line: String, needle: String): List<IntRange> {
        var index = 0
        var out: MutableList<IntRange>? = null
        while (index <= line.length - needle.length) {
            val hit = line.indexOf(needle, index, ignoreCase = true)
            if (hit < 0) break
            (out ?: mutableListOf<IntRange>().also { out = it })
                .add(hit until hit + needle.length)
            index = hit + needle.length
        }
        return out ?: emptyList()
    }
}
