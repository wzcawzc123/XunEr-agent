package io.github.mangi.eta.agent.cost

/**
 * 某个会话（或全局）的 token 用量汇总。
 *
 * 为什么单独立这一类而不是直接在 UI 里累加：
 * - 「可计费输入」的算法（input − cached）容易写错，放在一处便于测试与复用；
 * - 命中率是**最重要**的指标（缓存是否被破坏直接体现在这里），需要明确的定义与下限保护；
 * - 将来接价格表时，只需在这里加字段，UI 不用改。
 *
 * 所有字段用 Long：真机数据里单会话输入已达十亿级，Int 会溢出。
 */
internal data class AgentCostSummary(
    val turns: Int,
    val inputTokens: Long,
    val cachedTokens: Long,
    val outputTokens: Long,
    val reasoningTokens: Long,
    /** U3（v3.13.4）：按模型分组的用量；空列表表示未启用分组查询。 */
    val byModel: List<ByModel> = emptyList(),
) {
    /** 单个模型的用量小结（modelId 可能为空 = 老数据未绑定模型）。 */
    data class ByModel(
        val modelId: String?,
        val turns: Int,
        val inputTokens: Long,
        val cachedTokens: Long,
        val outputTokens: Long,
        val reasoningTokens: Long,
    ) {
        val displayName: String get() = modelId?.takeIf { it.isNotBlank() } ?: "未绑定模型"
        val billableInputTokens: Long get() = (inputTokens - cachedTokens).coerceAtLeast(0L)
    }

    /** 实际按全价计费的输入（缓存未命中的部分）。 */
    val billableInputTokens: Long get() = (inputTokens - cachedTokens).coerceAtLeast(0L)

    /**
     * 缓存命中率，0f..1f。
     *
     * 没有输入时返回 0 —— 显示成「0%」比「NaN%」或「100%」都更不容易让人误解。
     */
    val cacheHitRate: Float
        get() = if (inputTokens <= 0L) 0f else (cachedTokens.toFloat() / inputTokens.toFloat()).coerceIn(0f, 1f)

    /** 是否为「还没有任何用量」的空态：UI 据此决定是否显示整块统计。 */
    val isEmpty: Boolean get() = turns <= 0 && inputTokens <= 0L

    /** 一条用量记录的最小形态；与存储解耦，便于纯 JVM 测试。 */
    data class Row(
        val inputTokens: Long,
        val cachedTokens: Long?,
        val outputTokens: Long,
        val reasoningTokens: Long?,
    )

    companion object {
        val EMPTY = AgentCostSummary(0, 0L, 0L, 0L, 0L)

        /**
         * 从逐轮用量累加。
         *
         * 用 `List<Row>` 这种最小结构而不是直接依赖 Room 实体：这一层保持零 Android 依赖，
         * 纯 JVM 即可测试；调用方负责把实体映射成 [Row]。
         */
        fun fromRows(rows: List<Row>): AgentCostSummary {
            var turns = 0
            var input = 0L
            var cached = 0L
            var output = 0L
            var reasoning = 0L
            for (row in rows) {
                // 只统计真正发出过请求的轮次：没有 input 的记录是本地提示/占位，不该计入。
                if (row.inputTokens <= 0L) continue
                turns += 1
                input += row.inputTokens
                // 个别服务商不回 cached_tokens（null）；按 0 计，命中率因此偏保守而不是虚高。
                cached += (row.cachedTokens ?: 0L).coerceAtLeast(0L)
                output += row.outputTokens.coerceAtLeast(0L)
                reasoning += (row.reasoningTokens ?: 0L).coerceAtLeast(0L)
            }
            return AgentCostSummary(
                turns = turns,
                inputTokens = input,
                cachedTokens = cached,
                outputTokens = output,
                reasoningTokens = reasoning,
            )
        }

    }
}
