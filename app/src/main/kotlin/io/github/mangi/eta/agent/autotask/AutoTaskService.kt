package io.github.mangi.eta.agent.autotask

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.data.db.EtaDatabase

/**
 * 自动任务的执行服务：由 **root 守护脚本**通过 `am startservice` 唤醒。
 *
 * 为什么不挂在 `AgentRuntimeService` 上：那是绑定式服务（`ACTION_BIND`），
 * 需要一个存活的长连接客户端；而唤醒源是一个 shell 脚本，没有 binder。
 * 单开一个 startService 型服务，语义清楚：**收到 intent 就跑一批，跑完自己停**。
 *
 * 生命周期：
 * ```
 * am startservice → onCreate → onStartCommand → 干活 → stopSelf
 * ```
 * 前台化是为了避免被系统当作"后台服务"直接杀掉 —— 干活期间给一条低优先通知。
 *
 * 权限边界：**只有 root 能 `am startservice` 到它**（服务不导出），
 * 所以不需要额外鉴权 —— 能调到它的进程本身就是 root。
 */
internal class AutoTaskService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundSafely()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // **必须在后台线程做**：onStartCommand 跑在主线程，而 Room 禁止主线程访问数据库
        // （跑查询会抛 IllegalStateException: Cannot access database on the main thread）。
        // 之前整个流程放在主线程 + runCatching 里 → 异常被吞、什么都没执行，
        // 表现就是「Service 启动了但 pending.txt 不生成」。真机实测才发现。
        Thread({
            runCatching {
                val database = EtaDatabase.get(applicationContext)
                // 自愈：pending.txt 是数据库的投影，可能因为升级/清理/异常而与库不同步。
                // 每次被唤醒都重写一次，保证脚本下次读到的是最新状态。
                AutoTaskStore(database.agentTaskDao()).exportTo(applicationContext)
                val runtime = SimpleAutoTaskRuntime(database)
                runtime.tryRunOnce(applicationContext)
                // 清掉所有待执行标记：本次唤醒已处理完，不清的话任务只会触发一次。
                // 失败路径也清 —— 否则一条持续失败的任务会每次唤醒都重试，无限循环。
                clearAllRunMarkers()
            }.onFailure {
                // 记录 message 与首帧堆栈：只记 simpleName 时无法定位（这次的教训）。
                AndroidAgentLogger.warnThrottled("auto_task_service_failed") {
                    "AutoTaskService 执行失败: ${it.javaClass.simpleName}: ${it.message}; " +
                        "at ${it.stackTrace.firstOrNull()?.toString() ?: "?"}"
                }
            }
            stopSelf(startId)
        }, "auto-task-run").start()
        // 不要 START_STICKY：本服务由外部按需唤醒，不该被系统反复重建
        return START_NOT_STICKY
    }

    /** 清空待执行标记文件（本次唤醒的所有标记都已处理）。 */
    private fun clearAllRunMarkers() {
        runCatching {
            val file = AutoTaskExport.runMarkerFile(applicationContext)
            if (file.exists()) file.delete()
        }
    }

    private fun startForegroundSafely() {
        runCatching {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                manager.getNotificationChannel(CHANNEL_ID) == null
            ) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "自动任务执行中", NotificationManager.IMPORTANCE_MIN)
                        .apply {
                            description = "自动任务运行时的常驻通知，任务结束即消失"
                            setShowBadge(false)
                        },
                )
            }
            val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("自动任务执行中")
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setOngoing(true)
                .build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    FOREGROUND_ID,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(FOREGROUND_ID, notification)
            }
        }
    }

    companion object {
        private const val CHANNEL_ID = "agent_auto_task_run"
        private const val FOREGROUND_ID = 0x7A01

        /** 脚本用的启动命令（可直接贴进守护脚本）。 */
        fun startCommand(): String =
            "am startservice -n io.github.mangi.eta/${AutoTaskService::class.java.name}"
    }
}
