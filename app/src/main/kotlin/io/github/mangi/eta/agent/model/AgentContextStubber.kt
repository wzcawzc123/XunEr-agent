package io.github.mangi.eta.agent.model

import org.json.JSONArray

/**
 * L1 软水位折叠：把「最近两轮用户对话之前」的旧工具结果替换为占位符。
 *
 * 上下文压缩 v3 设计：
 * - **无 LLM**，确定性、零成本；只在软水位触发**一次**（批量），绝不每轮改（避免缓存逐轮失效）；
 * - 保护最近两轮用户对话及其后的内容不动——模型仍在「最近指令」的语境里；
 * - 折叠标记带原长度 + 120 字预览，且 `tool_call_id` 字段原样保留，模型可据此**重跑原工具**恢复全文。
 */
internal object AgentContextStubber {

    const val STUB_PREFIX = "[上下文折叠]"

    const val STUB_PREVIEW_CHARS = 120

    /**
     * @return 实际折叠的工具结果条数；不足两轮用户对话时为 0（不动）。
     */
    fun stub(messages: JSONArray, systemCount: Int): Int {
        val size = messages.length()
        val userIndexes = (systemCount until size).filter {
            messages.getJSONObject(it).optString("role") == "user"
        }
        if (userIndexes.size < 2) return 0
        val protectFrom = userIndexes[userIndexes.size - 2]

        var folded = 0
        for (index in systemCount until protectFrom) {
            val message = messages.getJSONObject(index)
            if (message.optString("role") != "tool") continue
            val content = message.optString("content")
            if (content.startsWith(STUB_PREFIX)) continue
            val preview = content.replace(Regex("\\s+"), " ").trim().take(STUB_PREVIEW_CHARS)
            message.put("content", "$STUB_PREFIX 原 ${content.length} 字符，如需要完整内容请重跑该工具：$preview …")
            folded++
        }
        return folded
    }
}
