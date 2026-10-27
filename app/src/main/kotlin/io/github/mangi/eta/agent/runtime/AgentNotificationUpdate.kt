package io.github.mangi.eta.agent.runtime

/**
 * 通知正文片段与更新节流的纯逻辑（2026-10-28 通知显示执行输出）。
 *
 * 平台事实（AOSP NotificationManagerService）：
 * - 入队限速默认 5 条/秒（EWMA），超限**静默丢弃**——没有异常、没有回调；
 * - 限速只作用于同 key 且 progressState 不变的更新，状态切换不受限。
 *
 * 因此策略是：**内容更新节流**（[delaySince]）+ **最后一次由补发保证落地**（urgent 路径）。
 */
internal object AgentNotificationUpdate {

    /** 内容更新的最小间隔：远低于 5/s 限速，留余量给同包其他通知。 */
    const val MIN_CONTENT_INTERVAL_MS = 1_000L

    /** 距下次允许发送还需等待的毫秒；0 = 可以立即发。上限一个周期（时钟回拨/异常保护）。 */
    fun delaySince(lastAt: Long, now: Long, minIntervalMs: Long = MIN_CONTENT_INTERVAL_MS): Long =
        (lastAt + minIntervalMs - now).coerceIn(0L, minIntervalMs)

    /**
     * 通知正文片段：单行化 + 取尾部（"最近在说什么"），超长前置省略号。
     * 空白内容返回空串，调用方据此退回阶段文案。
     */
    fun tailSnippet(raw: String, maxChars: Int = MAX_SNIPPET_CHARS): String {
        val flat = raw.replace(Regex("\\s+"), " ").trim()
        if (flat.isEmpty()) return ""
        return if (flat.length <= maxChars) flat else "…" + flat.takeLast(maxChars)
    }

    const val MAX_SNIPPET_CHARS = 80
}
