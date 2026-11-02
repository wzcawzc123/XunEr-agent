package io.github.mangi.eta.agent.memory

import io.github.mangi.eta.data.repository.AgentMemorySnapshot
import org.json.JSONObject

/**
 * 核心区水位回报（`memory_get` 的 `core_usage` 块）。
 *
 * ## 口径必须与注入侧一致
 *
 * 核心区注入走 [AgentMemoryContextBuilder.build]，用的是**会话模型的有效窗口**。
 * 工具层如果图省事传 null（落到 128k 默认基准），窗口一偏离 128k 两边就报不同的百分比：
 * 注入侧按 32k 窗口算「23%」，工具按 128k 分母算「93%」—— 模型据此"瘦身"，
 * 实际把一个本来健康的核心区砍掉。
 *
 * 所以这里刻意**复用同一个 builder**，而不是自己再算一遍：数字天然对齐，
 * 将来预算公式改了也不会漏掉这一处。
 */
internal object AgentMemoryGauge {
    /**
     * @param snapshot 与注入侧同源的记忆快照
     * @param contextWindow 会话模型的有效上下文窗口；null 时与注入侧同样落到默认基准
     */
    fun report(snapshot: AgentMemorySnapshot, contextWindow: Int?): JSONObject {
        val context = AgentMemoryContextBuilder.build(snapshot, contextWindow)
        val used = context.coreContent.length
        val budget = context.coreBudgetChars
        val percent = if (budget <= 0) 0 else (used * 100.0 / budget).toInt()
        return JSONObject()
            .put("used_chars", used)
            .put("budget_chars", budget)
            .put("percent", percent)
            .put("truncated", context.coreTruncated)
            .put("window", contextWindow ?: AgentMemoryContextBuilder.DEFAULT_CONTEXT_WINDOW)
            .put("note", NOTE)
    }

    private const val NOTE =
        "used/budget 的口径与自动注入完全一致。percent 接近 100 或 truncated=true 时，" +
            "核心区超出部分不会被注入，应把长期不用的内容移出「# 核心记忆」或精简措辞。"
}
