package io.github.mangi.eta.agent.runtime

/**
 * 通知的"活起来"参数（2026-10-28 A 方案，全走系统 Live Updates）。
 *
 * 约束来自平台文档：chip ≤7 字 / 96dp；通知更新限速 5/s；提升资格与样式无关但不可 colorized。
 * 这里的计算全部是纯函数，便于单测锁定口径。
 */
internal object AgentNotificationMotion {

    /** chip 轮播节奏：OPPO 人因研究给出的单行最优显示时长 3.5 秒。 */
    const val CHIP_ROTATE_MS = 3_500L

    /** 阶段内进度最多推进到 90%，留尾段表示"还没结束"，避免看起来已满。 */
    const val MAX_STAGE_FRACTION = 0.9f

    /** 思考段预期时长：8 秒走完 90%。 */
    const val REASONING_EXPECT_MS = 8_000L

    /** 工具段预期时长：10 秒走完 90%。 */
    const val TOOL_EXPECT_MS = 10_000L

    /** 回答段按字符数推进：600 字走完 90%（与常见回复长度相当）。 */
    const val ANSWER_CHARS_FOR_FULL = 600f

    /**
     * 阶段内进度：base（段起点）+ 段长 × 进度比例，比例夹在 [0, 0.9]。
     *
     * @param fraction 已完成比例：回答段是"已生成字数/目标字数"，思考/工具段是"已用时间/预期时长"
     */
    fun stageProgress(stageBase: Int, stageSpan: Int, fraction: Float): Int =
        stageBase + (stageSpan * fraction.coerceIn(0f, MAX_STAGE_FRACTION)).toInt()

    /** 时间驱动的比例（思考/工具段），夹在 [0,1]。 */
    fun elapsedFraction(elapsedMs: Long, expectedMs: Long): Float =
        if (expectedMs <= 0L) 1f else (elapsedMs.toFloat() / expectedMs).coerceIn(0f, 1f)

    /**
     * chip 文案轮播：每 [CHIP_ROTATE_MS] 切一条，空串被过滤（官方语义：空串=不显示）。
     * 返回 null 表示不应设置 chip 文本。
     */
    fun chipText(nowMillis: Long, variants: List<String>): String? {
        val clean = variants.filter { it.isNotBlank() }.distinct()
        if (clean.isEmpty()) return null
        val index = ((nowMillis / CHIP_ROTATE_MS) % clean.size).toInt()
        return clean[index]
    }
}
