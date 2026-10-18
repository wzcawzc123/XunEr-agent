package io.github.mangi.eta.agent.autotask

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.PowerManager

/**
 * 采集设备当前状态，供 [TaskCondition] 判定。
 *
 * 为什么单独立一个类、而不是在触发器里直接读：
 * 1. **可测**：判定逻辑（`TaskCondition.satisfiedBy`）已经是纯的，
 *    这里只负责"把系统状态翻译成 [DeviceState]"，边界清晰；
 * 2. **可替换**：将来若要接"用户手动模拟状态"或"测试桩"，只换这一层；
 * 3. **一次调用取全**：三个状态分别读三个系统服务，合一可取可减少竞态
 *    （比如读到"充电中"但下一毫秒就拔了 —— 这一轮本来就是尽力而为）。
 */
internal object DeviceStateReader {

    fun read(context: Context): DeviceState = DeviceState(
        charging = isCharging(context),
        onWifi = isOnWifi(context),
        screenOn = isScreenOn(context),
    )

    /**
     * 是否正在充电（含无线/USB/快充）。
     *
     * 用 `ACTION_BATTERY_CHANGED` 的 sticky intent 而不是注册监听：
     * sticky 会立刻返回当前值，不需要等待下一次广播。
     */
    fun isCharging(context: Context): Boolean {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return false
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        return status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
    }

    /**
     * 是否连着 WiFi 且能上网。
     *
     * **不能只看 activeNetwork**：开着 VPN 时默认网络是 tun0，
     * `hasTransport(TRANSPORT_WIFI)` 为 false → 条件永不满足。
     * 真机上（本机长期挂 VPN）表现为「脚本判定到点、App 判定条件不满足、任务永不执行」。
     *
     * 也不能只依赖 ConnectivityManager：守护脚本读的是 sysfs（wlan0=up），
     * 两边口径不一致会让人以为「明明连着 WiFi」。
     * 这里做两层判断：默认网络是 WiFi 就通过；否则遍历所有网络找已连接的 WiFi。
     */
    fun isOnWifi(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false

        fun NetworkCapabilities.isWifi(): Boolean =
            hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)

        cm.activeNetwork?.let { network ->
            cm.getNetworkCapabilities(network)?.let { if (it.isWifi()) return true }
        }
        // VPN / 蜂窝作默认网络时，WiFi 仍在 allNetworks 里
        return cm.allNetworks.any { network ->
            cm.getNetworkCapabilities(network)?.isWifi() == true
        }
    }

    /** 屏幕是否亮着。 */
    fun isScreenOn(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        // isInteractive 兼容 Doze：屏幕灭且设备休眠时返回 false
        return pm.isInteractive
    }
}
