package io.github.mangi.eta.agent.tool

/**
 * 坐标点击的新鲜度判定。
 *
 * 背景：节点动作（tap_element）有完整的 STALE_* 校验，但坐标动作（tap/tap_area/long_press）
 * 此前只校验坐标是否越界，不关心"观察之后屏幕是否已经变了"。真机实测：滚动页面后沿用旧坐标，
 * 点击 100% 落空且手势仍返回成功（静默失败），日志里查不到任何失败记录。
 *
 * 这里只在两类**证据确凿、误报率低**的情况判过期：
 *  - 我们自己滚动/滑动过（contentVersion 递增）
 *  - 观察前后前台应用不同（导航到了别的应用）
 *
 * 刻意**不**用"窗口内容代际"作为判据：事件流/列表会持续刷新，用它会导致活着的页面上
 * 所有坐标点击被误拦（实测 WebUI 事件列表每几秒刷新一次）。
 */
internal object CoordinateFreshnessPolicy {
    enum class Verdict { ALLOW, UNANCHORED, STALE_AFTER_SCROLL, STALE_WINDOW, STALE_RESOLUTION }

    fun evaluate(
        observedVersion: Long,
        currentVersion: Long,
        observedPackage: String?,
        currentPackage: String?,
        observedScreen: Pair<Int, Int>? = null,
        currentScreen: Pair<Int, Int>? = null,
    ): Verdict = when {
        // M2.2 减半版（2026-10-04 决策）：本 run 从未 observe/locate 过 = 坐标无来源，
        // 判链首。刻意只覆盖 tap/tap_area/long_press 三工具：swipe 的连续翻页是合法
        // 流程（画廊/长列表），上锚定会强制滑动间插 observe，误伤真实场景。
        observedVersion < 0 -> Verdict.UNANCHORED
        // 分辨率切换（FHD 1080x2412 ↔ 2K 1440x3216）让全部旧坐标失效，且不伴随滚动或切窗；
        // 真机实测（内存管理模块会话横跨切换点）导致系统性点偏，必须最先判定。
        observedScreen != null && currentScreen != null && observedScreen != currentScreen -> Verdict.STALE_RESOLUTION
        observedVersion >= 0 && currentVersion != observedVersion -> Verdict.STALE_AFTER_SCROLL
        !observedPackage.isNullOrBlank() &&
            !currentPackage.isNullOrBlank() &&
            observedPackage != currentPackage -> Verdict.STALE_WINDOW
        else -> Verdict.ALLOW
    }

    fun message(verdict: Verdict): String = when (verdict) {
        Verdict.UNANCHORED ->
            "本会话尚未 observe_screen/locate_on_screen，坐标无观察来源；请先观察屏幕再点击"
        Verdict.STALE_AFTER_SCROLL ->
            "屏幕已滚动或被滑动过，坐标相对上次 observe_screen 已过期；请重新 observe_screen 获取新坐标"
        Verdict.STALE_WINDOW ->
            "前台应用已变化，坐标已过期；请重新 observe_screen"
        Verdict.STALE_RESOLUTION ->
            "屏幕分辨率已变化，全部旧坐标失效；请重新 observe_screen 获取新的 coordinate_contract 后再点击"
        Verdict.ALLOW -> ""
    }
}
