package io.github.mangi.eta.agent.cost

import io.github.mangi.eta.data.db.ConversationDao

/**
 * 用量统计的读取入口。
 *
 * 只做一件事：把 Room 的聚合投影转成 [AgentCostSummary]。
 * 之所以单独一层而不是让 UI 直接调 DAO：
 * - UI 层不该知道 `TokenUsageProjection` 这种存储细节；
 * - 转换逻辑（字段名映射、脏数据兜底）可以在这里统一处理并被测试覆盖；
 * - 将来加"按模型分组""按时间窗口"时，只改这里。
 */
internal class AgentCostRepository(private val dao: ConversationDao) {

    /** 单个会话的用量；会话不存在或从未发过请求时返回 [AgentCostSummary.EMPTY]。 */
    fun forConversation(conversationId: String): AgentCostSummary {
        if (conversationId.isBlank()) return AgentCostSummary.EMPTY
        val projection = runCatching { dao.tokenUsageFor(conversationId) }.getOrNull()
            ?: return AgentCostSummary.EMPTY
        return projection.toSummary()
    }

    /** 全部会话的累计用量（「总账」）。 */
    fun all(): AgentCostSummary {
        val projection = runCatching { dao.tokenUsageAll() }.getOrNull()
            ?: return AgentCostSummary.EMPTY
        return projection.toSummary()
    }

    /**
     * 投影 → 领域模型。
     *
     * `COUNT(*)` 在无匹配行时返回 0 而不是 null，但 SUM 是 null，DAO 里已 COALESCE，
     * 这里再做一次 coerceAtLeast 兜底 —— 统计数字宁可偏小，也不要显示负数。
     */
    private fun ConversationDao.TokenUsageProjection.toSummary(): AgentCostSummary = AgentCostSummary(
        turns = turns,
        inputTokens = inputTokens.coerceAtLeast(0L),
        cachedTokens = cachedTokens.coerceAtLeast(0L),
        outputTokens = outputTokens.coerceAtLeast(0L),
        reasoningTokens = reasoningTokens.coerceAtLeast(0L),
    )
}
