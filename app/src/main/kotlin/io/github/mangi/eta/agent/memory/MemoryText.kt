package io.github.mangi.eta.agent.memory

/**
 * 记忆文本的公共口径：核心标题与安全截断的唯一来源。
 *
 * 这些常量曾经在注入、预览、写入三处各有一份拷贝，口径漂移时是**静默出错**：
 * 预览认的「核心记忆」和注入认的不是同一行，用户看到的和模型看到的就不一样了。
 */
internal object MemoryText {
    /** 自动注入 prompt 的核心段标题（唯一口径）。 */
    const val CORE_HEADING = "# 核心记忆"

    /**
     * 截断但**不切断 UTF-16 代理对**。
     *
     * `String.take(n)` 可能正好切在 emoji 的高位代理上，留下一个孤立代理 ——
     * 渲染成 `�`，模型看到的就是一个损坏字符。预算边界本来就是个近似值，
     * 少一个字符远比留半个字符好。
     */
    fun safeTake(text: String, limit: Int): String {
        if (limit <= 0) return ""
        if (text.length <= limit) return text
        val end = if (Character.isHighSurrogate(text[limit - 1])) limit - 1 else limit
        return text.substring(0, end)
    }
}
