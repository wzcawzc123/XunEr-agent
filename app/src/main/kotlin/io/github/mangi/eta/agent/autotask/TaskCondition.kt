package io.github.mangi.eta.agent.autotask

/**
 * 自动任务的触发条件。
 *
 * 为什么要组合多个条件，而不是一个"定时"：
 * Android 会冻结后台定时器（Doze），说好 9:00 可能 10:30 才醒、或根本不醒。
 * 而「充电 + 连 WiFi + 屏幕灭」是系统**明确允许**的后台窗口 ——
 * 不追求准时，只追求"条件满足时顺带做了"。
 *
 * 全部条件默认 `true`：新任务默认最保守，必须主动放宽才更容易触发。
 */
internal data class TaskCondition(
    /** 必须正在充电。屏幕亮着时耗电高，充电是最可靠的窗口。 */
    val requireCharging: Boolean = true,
    /** 必须连 WiFi。移动网络下跑任务可能产生流量费用。 */
    val requireWifi: Boolean = true,
    /** 必须屏幕熄灭。亮屏时用户可能正在用，跑任务会抢资源。 */
    val requireScreenOff: Boolean = true,
) {
    /**
     * 判断当前设备状态是否满足条件。
     *
     * @param state 由调用方采集，便于测试注入（不直接读 Android API）。
     */
    fun satisfiedBy(state: DeviceState): Boolean =
        (!requireCharging || state.charging) &&
            (!requireWifi || state.onWifi) &&
            (!requireScreenOff || !state.screenOn)

    /** 给用户读的一句话，用于设置页与通知。 */
    fun describe(): String {
        val parts = buildList {
            if (requireCharging) add("充电中")
            if (requireWifi) add("连 WiFi")
            if (requireScreenOff) add("屏幕熄灭")
        }
        return if (parts.isEmpty()) "随时" else parts.joinToString(" + ")
    }

    /** 持久化用：1=充电 2=WiFi 4=灭屏。 */
    fun toMask(): Int {
        var mask = 0
        if (requireCharging) mask = mask or BIT_CHARGING
        if (requireWifi) mask = mask or BIT_WIFI
        if (requireScreenOff) mask = mask or BIT_SCREEN_OFF
        return mask
    }

    companion object {
        const val BIT_CHARGING = 1
        const val BIT_WIFI = 2
        const val BIT_SCREEN_OFF = 4

        /** 从位掩码还原。未知位忽略（向前兼容：将来加的条件在旧版本里读不出来就当没要求）。 */
        fun fromMask(mask: Int): TaskCondition = TaskCondition(
            requireCharging = mask and BIT_CHARGING != 0,
            requireWifi = mask and BIT_WIFI != 0,
            requireScreenOff = mask and BIT_SCREEN_OFF != 0,
        )

        /** 最严格（默认）。 */
        val CONSERVATIVE = TaskCondition()

        /** 只要连 WiFi（充电中不一定灭屏，适合需要快速响应的场景）。 */
        val WIFI_ONLY = TaskCondition(requireCharging = false, requireScreenOff = false)

        /** 不做限制 —— 仅供用户在明确知情时选择（耗电、可能被打断）。 */
        val ANYTIME = TaskCondition(false, false, false)
    }
}

/** 设备当前状态的最小快照；由运行时采集，测试直接构造。 */
internal data class DeviceState(
    val charging: Boolean,
    val onWifi: Boolean,
    val screenOn: Boolean,
)
