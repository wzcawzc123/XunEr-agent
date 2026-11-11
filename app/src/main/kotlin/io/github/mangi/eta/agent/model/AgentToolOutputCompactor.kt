package io.github.mangi.eta.agent.model

/**
 * L0 入口压缩：工具结果在**进入历史之前**做确定性瘦身（不调 LLM）。
 *
 * 设计依据（2026-11 上下文压缩 v3）：
 * - 「最便宜的 token 是根本没生成的 token」（Anthropic 工具结果清理的哲学）；
 * - 入口压缩发生在消息构造前，**不改写已有历史** → 对前缀缓存零影响；
 * - 纯字符串操作，确定性、零成本、可单元测试。
 *
 * 策略：短输出原样；超长输出只保留头 + 尾，中间替换为省略标记（含行数），
 * 并顺带抽取错误/异常行，让模型一眼看到「哪里失败了」。全文仍在工具侧可重查。
 */
internal object AgentToolOutputCompactor {

    /** 低于此长度原样返回，零开销。 */
    const val SOFT_LIMIT_CHARS = 12_000

    const val KEEP_HEAD_CHARS = 1_600

    const val KEEP_TAIL_CHARS = 2_000

    /** 抽取的错误行最多保留几行、每行最多多长。 */
    private const val MAX_ERROR_LINES = 3
    private const val MAX_ERROR_LINE_CHARS = 240

    private val ERROR_PATTERN = Regex(
        "(Exception|Error|FAILED|failed|Fatal|fatal|Caused by|错误|失败|异常|non-zero exit|exit code|E/AndroidRuntime|FATAL EXCEPTION)",
    )

    fun compact(content: String): String {
        if (content.length <= SOFT_LIMIT_CHARS) return content
        val head = content.take(KEEP_HEAD_CHARS)
        val tail = content.takeLast(KEEP_TAIL_CHARS)
        val omitted = content.length - head.length - tail.length
        val lineCount = content.count { it == '\n' } + 1
        val errors = extractErrors(content)
        return buildString {
            append(head)
            append("\n\n…[已省略中间 ${omitted} 字符（共约 ${lineCount} 行）；需要完整内容时用对应工具分段读取或重查]…\n")
            if (errors.isNotEmpty()) {
                append("\n⚠️ 关键错误/异常（节选）：\n")
                errors.forEach { append("- ").append(it).append('\n') }
            }
            append('\n')
            append(tail)
        }
    }

    private fun extractErrors(content: String): List<String> {
        val hits = mutableListOf<String>()
        for (line in content.lineSequence()) {
            if (ERROR_PATTERN.containsMatchIn(line)) {
                val trimmed = line.trim().take(MAX_ERROR_LINE_CHARS)
                if (trimmed.isNotEmpty()) hits += trimmed
                if (hits.size >= MAX_ERROR_LINES) break
            }
        }
        return hits
    }
}
