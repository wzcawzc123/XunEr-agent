package io.github.mangi.eta.agent.memory

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

/**
 * 记忆内容的时效性评估。
 *
 * 只**标记**、不隐藏、不修改 —— 判断"什么算过期"本质上依赖领域知识：
 * 「Debian 13」几年都成立，「当前在跑 v0.17」一周就可能过时，
 * 而两者在文本上长得一模一样。误判代价（隐藏了仍在用的信息）远高于漏判，
 * 所以这里只回答一个问题：**这段内容里最晚提到的日期距今多久**。
 *
 * 日期来源是内容里显式的 `YYYY-MM-DD`（本项目的记忆写作习惯：
 * "（2026-09-23 结项）"、"2026-10-01 起长期执行"）。
 */
internal object AgentMemoryFreshness {

    /** 与本项目记忆习惯一致的日期写法。 */
    private val DATE = Regex("""\b(20\d{2})-(\d{2})-(\d{2})\b""")

    /** 超过这个天数就提示"可能过时"。30 天：足够覆盖一个开发周期。 */
    const val STALE_DAYS = 30L

    data class Result(
        /** 内容里最晚出现的日期（ISO），没有则为 null。 */
        val latestDate: String?,
        /** 最晚日期距今的天数；无日期为 null。 */
        val ageDays: Long?,
        /** 是否超过 [STALE_DAYS]。 */
        val stale: Boolean,
    ) {
        /** 给模型看的一句话；没有日期时返回 null（不打扰）。 */
        fun hint(): String? = when {
            latestDate == null -> null
            stale -> "这段内容最晚提到 $latestDate（$ageDays 天前），其中「当前状态」类描述可能已过时；引用前请核实。"
            else -> null
        }
    }

    /**
     * 评估 [content] 相对于 [today] 的时效。
     *
     * @param today 由调用方注入而不是内部取 `LocalDate.now()`：这样测试可固定时间，
     *   也便于将来用"会话最后活动时间"代替系统时间。
     */
    fun assess(content: String, today: LocalDate): Result {
        val dates = DATE.findAll(content).mapNotNull { m ->
            runCatching {
                LocalDate.parse(
                    "%s-%s-%s".format(m.groupValues[1], m.groupValues[2], m.groupValues[3]),
                    DateTimeFormatter.ISO_LOCAL_DATE,
                )
            }.getOrNull()
        }.toList()

        if (dates.isEmpty()) return Result(null, null, false)

        val latest = dates.max()
        // 未来日期（比如计划中的时间点）不算过时，按 0 天处理。
        val age = ChronoUnit.DAYS.between(latest, today).coerceAtLeast(0L)
        return Result(
            latestDate = latest.toString(),
            ageDays = age,
            stale = age > STALE_DAYS,
        )
    }
}
