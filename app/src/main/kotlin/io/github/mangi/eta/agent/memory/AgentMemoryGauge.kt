package io.github.mangi.eta.agent.memory

import io.github.mangi.eta.data.repository.AgentMemorySnapshot
import org.json.JSONObject

/**
 * 核心区水位回报（memory_get 的 core_usage 块）。
 *
 * **口径必须与注入侧一致**：注入走 [ConversationMemoryPins.contextFor]，用的是会话模型的有效窗口；
 * 工具层曾经图省事传 null（= 128k 默认窗口），窗口一偏离 128k 两边就报不同的百分比
 * （真机 2026-10-28：注入侧 7507/32000≈23%，工具却按 8000 分母报 93%，差点误导用户去"瘦身"）。
 *
 * 修复后 [AgentLocalTools][io.github.mangi.eta.agent.tool.AgentLocalTools] 通过
 * contextWindow provider 传入真实窗口，本对象与注入侧共用 [AgentMemoryContextBuilder] 同一套
 * 预算与截断逻辑，因此 used/budget/truncated 三个数字天然对齐。
 */
internal object AgentMemoryGauge {

    /**
     * @param snapshot 内存快照（与注入侧同源）
     * @param contextWindow 会话模型的有效上下文窗口；null 时与注入侧同样落到默认窗口（128k）
     */
    fun payload(snapshot: AgentMemorySnapshot, contextWindow: Int?): JSONObject {
        val gauge = AgentMemoryContextBuilder.build(snapshot, contextWindow)
        val used = gauge.coreContent.length
        val budget = gauge.coreBudgetChars
        return JSONObject()
            .put("used_chars", used)
            .put("budget_chars", budget)
            .put("percent", if (budget > 0) used * 100 / budget else 0)
            .put("truncated", gauge.coreTruncated)
            .put("sections", gauge.coreSectionCount)
            .put("injected_lines", gauge.coreInjectedLines)
            .put("total_lines", gauge.coreTotalLines)
    }
}
