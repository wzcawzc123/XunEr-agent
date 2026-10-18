package io.github.mangi.eta.agent.autotask

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log

/**
 * 自动任务的"点火器"：监听系统状态变化，条件满足时尝试执行一条任务。
 *
 * 为什么只用广播、不引入 WorkManager：
 * - WorkManager 的 periodic 最小周期 15 分钟，本质是"定时"，而定时在 Doze 下不可靠；
 * - 真正可靠的时机只有两类：**系统允许唤醒的事件**（充电接入/断开、屏幕熄灭、网络变化）
 *   与**用户主动打开 App**。前者由本类覆盖，后者由 App 启动流程覆盖。
 * - 少一个依赖 = 少一处后台策略冲突（本项目已在和厂商后台管理打交道）。
 *
 * 设计约束（与自动任务的三道护栏一致）：
 * 1. **只做决策，不做执行** —— 执行交给 [AutoTaskRuntime]，便于测试与替换；
 * 2. **任何异常都吞掉并记日志** —— 广播接收器抛异常会导致系统警告甚至禁用该接收器；
 * 3. **一次唤醒只处理一条** —— 由 [AutoTaskScheduler] 保证。
 */
internal class AutoTaskTrigger(
    private val runtime: AutoTaskRuntime,
) {
    private var connectivityCallback: ConnectivityManager.NetworkCallback? = null
    private var registeredContext: Context? = null

    /** 我关心的系统事件。屏幕与电源都是系统级广播，无需权限。 */
    val filter: IntentFilter = IntentFilter().apply {
        addAction(Intent.ACTION_POWER_CONNECTED)
        addAction(Intent.ACTION_POWER_DISCONNECTED)
        addAction(Intent.ACTION_SCREEN_OFF)
        addAction(Intent.ACTION_SCREEN_ON)
    }

    /** 静态注册用（Manifest）还是动态注册都会走这里。 */
    fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        // 只处理关心的动作：动态注册时过滤器已保证，但静态注册可能被别的 intent 触发
        if (!filter.hasAction(action)) return
        runCatching { runtime.tryRunOnce(context) }
            .onFailure { Log.w(TAG, "自动任务触发失败（$action）", it) }
    }

    /**
     * 额外注册网络变化回调。
     *
     * 网络变化没有"广播"可用（ConnectivityManager 回调更准），单独挂一个。
     * 用 [registerContext] 记住 Context 以便 [unregister] 时注销。
     */
    fun registerNetwork(context: Context) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        if (connectivityCallback != null) return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                runCatching { runtime.tryRunOnce(context) }
                    .onFailure { Log.w(TAG, "自动任务触发失败（network）", it) }
            }
        }
        runCatching {
            cm.registerDefaultNetworkCallback(callback)
            connectivityCallback = callback
            registeredContext = context.applicationContext
        }.onFailure { Log.w(TAG, "注册网络回调失败", it) }
    }

    fun unregister() {
        val callback = connectivityCallback ?: return
        val ctx = registeredContext ?: return
        runCatching {
            (ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)
                ?.unregisterNetworkCallback(callback)
        }
        connectivityCallback = null
        registeredContext = null
    }

    companion object {
        private const val TAG = "AutoTaskTrigger"
    }
}

/**
 * 执行入口。真实实现由运行时提供（需要启动 Agent 的一轮对话），
 * 抽成接口是为了让 [AutoTaskTrigger] 可测 —— 测试里塞一个假实现即可。
 */
internal interface AutoTaskRuntime {
    /**
     * 读任务 → 读状态 → 决策 → 若有任务则执行。
     *
     * 实现必须保证：**并发调用安全**（多个广播可能同时到达），
     * 且一次只执行一条任务。
     */
    fun tryRunOnce(context: Context)
}
