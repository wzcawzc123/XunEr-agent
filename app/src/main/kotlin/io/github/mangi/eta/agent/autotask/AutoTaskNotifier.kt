package io.github.mangi.eta.agent.autotask

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import io.github.mangi.eta.R

/**
 * 自动任务结果通知。
 *
 * 设计取舍：**没有通知权限就静默跳过**，不弹任何权限请求。
 * 自动任务是后台行为，在用户没准备的时候弹权限框是冒犯；
 * 结果仍写进了任务表（`last_result`），用户下次打开设置页能看见。
 */
internal object AutoTaskNotifier {

    private const val CHANNEL_ID = "agent_auto_task"
    private const val NOTIFICATION_ID = 0x7A00

    fun notifyFinished(context: Context, task: AgentTask, result: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        // 无权限直接跳过（Android 13+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            manager.areNotificationsEnabled().not()
        ) {
            return
        }

        runCatching {
            ensureChannel(manager)
            val body = result.lineSequence().firstOrNull { it.isNotBlank() }?.take(120) ?: "已完成"
            val notification: Notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("自动任务：${task.title}")
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(result.take(1_000)))
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .build()
            manager.notify(NOTIFICATION_ID + task.id.hashCode().and(0xFFFF), notification)
        }
    }

    private fun ensureChannel(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "自动任务",
                // LOW：不发声不打点，只在通知栏留一条 —— 自动任务是"顺带告诉你"，
                // 不该像即时消息那样打断
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "后台自动任务的执行结果"
                setShowBadge(false)
            },
        )
    }
}
