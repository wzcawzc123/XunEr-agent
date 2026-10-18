package io.github.mangi.eta.agent.autotask

/**
 * 提醒的**时间语义**。
 *
 * 借鉴 DeepSeek Harness 的 `schedule` 子系统（`after` / `every` / `daily` / `weekly` / `cron`），
 * 但只做前三类里最实用的三种 —— cron 表达式的解析与本地化成本远高于收益，
 * 而「延迟一次 / 固定间隔 / 每天某点」已覆盖绝大多数真实需求。
 *
 * 与 Android 的结合点在 [nextRunAtMillis]：
 * - 语义上保留"到点"（用户说"每天 9 点"就是 9 点）；
 * - 实际执行由条件触发（充电 + 灭屏）兜底 —— Doze 下定时器不可靠，
 *   所以判定改为「已过期 且 条件满足」，允许晚一点，但不提前。
 */
internal sealed interface ScheduleSpec {
    /**
     * 下一次应该执行的时刻（毫秒）。已过期的返回一个 <= now 的值，
     * 由调用方用 `now >= nextRunAtMillis` 判定"到点"。
     *
     * @param lastRunAt 上次执行时刻；null 表示从未执行
     */
    fun nextRunAtMillis(now: Long, lastRunAt: Long?): Long

    /** 给用户看的一句话。 */
    fun describe(): String

    /** 一次性任务执行后是否还应继续（daily/every 为 true）。 */
    val repeating: Boolean

    /** 延迟一次：`after(30 分钟)`。 */
    data class After(val delayMillis: Long, val anchor: Long) : ScheduleSpec {
        init {
            require(delayMillis > 0) { "延迟必须为正" }
        }

        override fun nextRunAtMillis(now: Long, lastRunAt: Long?): Long = anchor + delayMillis
        override fun describe(): String = "创建后 ${formatDuration(delayMillis)}"
        override val repeating: Boolean get() = false
    }

    /** 固定间隔：`every(2 小时)`。 */
    data class Every(val intervalMillis: Long) : ScheduleSpec {
        init {
            require(intervalMillis > 0) { "间隔必须为正" }
        }

        /**
         * 首次触发也间隔一个周期：`now + interval`。
         *
         * 此前首次返回 `now`，含义是「建完立刻跑一次」。真机上表现为
         * 创建「每 5 分钟巡检」的瞬间就烧掉一轮 Agent —— 用户还没准备好，
         * 也没等满一个周期，与「每 N 分钟」的字面语义不符。
         */
        override fun nextRunAtMillis(now: Long, lastRunAt: Long?): Long =
            if (lastRunAt == null) now + intervalMillis else lastRunAt + intervalMillis

        override fun describe(): String = "每 ${formatDuration(intervalMillis)}"
        override val repeating: Boolean get() = true
    }

    /**
     * 每天某个时刻（本地时区）。
     *
     * 用 [hourOfDay] / [minuteOfHour] 而不是存绝对时间戳：跨天时能自然滚动，
     * 且用户改时区后行为符合直觉（"每天 9 点"就是当地的 9 点）。
     */
    data class Daily(val hourOfDay: Int, val minuteOfHour: Int) : ScheduleSpec {
        init {
            require(hourOfDay in 0..23) { "小时必须在 0..23" }
            require(minuteOfHour in 0..59) { "分钟必须在 0..59" }
        }

        /**
         * 返回「上次执行之后的下一个 [hourOfDay]:[minuteOfHour]」。
         *
         * **必须基于 lastRunAt 而不是 now** —— 审查时发现的 bug：
         * 原实现总是从 now 往后算，返回的永远在未来，于是 isDue() 恒为 false，
         * 每日任务**永远不会触发**。
         *
         * **从未执行过时以 now 为基准**（不是"一年前"）。
         * 用"一年前"做基准会返回一个历史时刻，导致新建任务立刻触发 ——
         * 用户下午 3 点建「每天 9 点」的任务，期望的是明天 9 点，而不是现在就跑。
         * 这个语义错误是写测试时发现的（断言"next 应在 0~1 天内"直接把它照出来了）。
         */
        override fun nextRunAtMillis(now: Long, lastRunAt: Long?): Long {
            val zone = java.time.ZoneId.systemDefault()
            val baseInstant = lastRunAt
                ?.let { java.time.Instant.ofEpochMilli(it) }
                ?: java.time.Instant.ofEpochMilli(now)
            var candidateLocal = baseInstant.atZone(zone).toLocalDate()
            var candidate = candidateLocal
                .atTime(hourOfDay, minuteOfHour)
                .atZone(zone)
                .toInstant()
            // 候选必须严格晚于基准；最多顺延两天（时区/DST 兜底）
            var guard = 0
            while (!candidate.isAfter(baseInstant) && guard < 3) {
                candidateLocal = candidateLocal.plusDays(1)
                candidate = candidateLocal
                    .atTime(hourOfDay, minuteOfHour)
                    .atZone(zone)
                    .toInstant()
                guard += 1
            }
            return candidate.toEpochMilli()
        }

        override fun describe(): String = "每天 %02d:%02d".format(hourOfDay, minuteOfHour)
        override val repeating: Boolean get() = true
    }

    companion object {
        /**
         * 编码成可持久化的字符串。
         *
         * 为什么不用 JSON / TypeConverter：这是个三态 sealed interface，
         * 用「kind + 两个整数」的紧凑单行就够，且**人眼可读** ——
         * 排查时能直接 `SELECT schedule_spec FROM agent_tasks` 看明白。
         *
         * **这个字段本该从一开始就有**：`AgentTask.schedule` 是域模型字段，
         * 但 Entity 里漏了一列，写入时被静默丢弃，读回来永远 null ——
         * 于是所有时间语义（after/every/daily）在真机上退化成「条件满足就跑」。
         * 只有真机端到端测试才暴露（pending.txt 里 nextRunAtMillis 恒为 0）。
         */
        fun encode(spec: ScheduleSpec?): String = when (spec) {
            null -> ""
            is After -> "after:${spec.delayMillis}:${spec.anchor}"
            is Every -> "every:${spec.intervalMillis}"
            is Daily -> "daily:${spec.hourOfDay}:${spec.minuteOfHour}"
        }

        /** 解析 [encode] 的产物；格式不认识时返回 null（旧数据/未来版本都安全降级）。 */
        fun decode(raw: String?): ScheduleSpec? {
            if (raw.isNullOrBlank()) return null
            val parts = raw.split(':')
            return runCatching {
                when (parts.getOrNull(0)) {
                    "after" -> After(parts[1].toLong(), parts[2].toLong())
                    "every" -> Every(parts[1].toLong())
                    "daily" -> Daily(parts[1].toInt(), parts[2].toInt())
                    else -> null
                }
            }.getOrNull()
        }

        /** 把毫秒差格式化成「30 分钟 / 2 小时 / 3 天」。 */
        fun formatDuration(millis: Long): String {
            val minutes = millis / 60_000
            return when {
                minutes < 1 -> "不到 1 分钟"
                minutes < 60 -> "$minutes 分钟"
                minutes < 60 * 24 -> "${minutes / 60} 小时${if (minutes % 60 > 0) " ${minutes % 60} 分钟" else ""}"
                else -> "${minutes / (60 * 24)} 天"
            }
        }
    }
}
