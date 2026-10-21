package io.github.mangi.eta.agent.memory

/**
 * 记忆文本的公共口径：核心标题与安全截断的唯一来源。
 *
 * 4.0 前 `CORE_HEADING` 有三份拷贝、`safeTake` 有两份拷贝，散落在
 * AgentMemoryContext / AgentMemoryPreview / AgentMemoryRepository —— 口径漂移时
 * 静默出错（例如预览认的「核心记忆」与注入认的不是同一行）。收敛到此。
 */
internal object MemoryText {
    /** 自动注入 prompt 的核心段标题（唯一口径）。 */
    const val CORE_HEADING = "# 核心记忆"

    /** 不在截断点切断 UTF-16 代理对（emoji 等）。 */
    fun safeTake(text: String, limit: Int): String {
        if (limit <= 0) return ""
        if (text.length <= limit) return text
        val end = if (Character.isHighSurrogate(text[limit - 1])) limit - 1 else limit
        return text.substring(0, end)
    }
}
