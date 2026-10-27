package io.github.mangi.eta.agent.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.overlay.AgentOverlayStatus
import io.github.mangi.eta.agent.overlay.localizedText
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.ui.MainActivity
import java.util.concurrent.atomic.AtomicLong

/** 只在用户任务存活期间持有前台执行生命周期；进程被系统停止后不重放任务。 */
internal class AgentExecutionService : Service() {
    private val stopQueue = ExecutionStopQueue { failure ->
        AndroidAgentLogger.warn("Execution task stop failed: type=${failure.safeLogType()}")
    }
    private val owner = ownerSequence.incrementAndGet()
    private var foregroundActive = false
    /** 上次 notify 的时间戳（节流用，见 [AgentNotificationUpdate]）。 */
    private var lastNotifyAt = 0L
    private var notifyScheduled = false
    @Volatile private var startRejected = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        leases.attachOwner(owner)
        val manager = getSystemService(NotificationManager::class.java)
        // 实时通知（Android 16 Live Updates / ColorOS 流体云）要求通道不是 IMPORTANCE_MIN；
        // 实测 OEM（ColorOS）还会额外要求更高重要性，LOW 会被静默拒绝提升。通道重要性创建后
        // 不可修改，故启用新 id：DEFAULT + 静音（不出声、不震动、无角标），观感与 LOW 一致但可被提升。
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.execution_channel), NotificationManager.IMPORTANCE_DEFAULT).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            },
        )
        // 旧通道（LOW）遗留会导致设置页出现重复条目；删除后由新通道接管。
        manager.deleteNotificationChannel(LEGACY_CHANNEL)
        ensureForeground()
    }

    private fun ensureForeground() {
        if (foregroundActive || startRejected) return
        leases.attachOwner(owner)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification())
            }
            foregroundActive = true
        } catch (failure: RuntimeException) {
            startRejected = true
            AndroidAgentLogger.warn("Execution service foreground failed: type=${failure.safeLogType()}")
            stopTasks(startFailed = true)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopTasks()
        } else {
            ensureForeground()
            refreshNotification(urgent = true)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (instance === this) instance = null
        // 销毁时同样收回本服务拥有的任务。回收在独立有界工作线程上完成，不阻塞 Main。
        stopQueue.close(leases.drainOwner(owner))
        super.onDestroy()
    }

    private fun stopTasks(startFailed: Boolean = false) {
        val callbacks = leases.drain(startFailed)
        stopQueue.submit(callbacks) {
            mainHandler.post { if (instance === this) refreshNotification(urgent = true) }
        }
    }

    /**
     * @param urgent 状态切换（启动/收尾）立即发，不受节流；内容更新走节流 + 补发保证末次必达。
     */
    private fun refreshNotification(urgent: Boolean = false) {
        if (leases.closeOwnerIfIdle(owner)) {
            foregroundActive = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        if (!urgent) {
            val wait = AgentNotificationUpdate.delaySince(lastNotifyAt, android.os.SystemClock.uptimeMillis())
            if (wait > 0) {
                if (!notifyScheduled) {
                    notifyScheduled = true
                    // 补发不再节流：流式内容每秒上百次 delta，平台超限是静默丢弃，
                    // 若最后一批内容被吞，通知会永远停在旧文字上。
                    mainHandler.postDelayed({
                        notifyScheduled = false
                        refreshNotification(urgent = true)
                    }, wait)
                }
                return
            }
        }
        lastNotifyAt = android.os.SystemClock.uptimeMillis()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
    }

    private fun notification(): Notification {
        val status = runStatus
        // 点击直达当前会话：带 OPEN_CONVERSATION_ID 意图（MainActivity 已支持该跳转）。
        val openIntent = Intent(this, MainActivity::class.java).apply {
            if (runConversationId.isNotBlank()) {
                setAction(MainActivity.ACTION_OPEN_CONVERSATION_ID)
                putExtra(MainActivity.EXTRA_CONVERSATION_ID, runConversationId)
            }
        }
        val open = PendingIntent.getActivity(
            this, if (runConversationId.isNotBlank()) 2 else 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, AgentExecutionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val taskCount = if (activeRunCount > 0) activeRunCount else leases.count()
        // 开关打开且正在生成时，正文显示流式输出尾部片段（最近在说什么）；否则退回阶段文案。
        val snippet = AgentNotificationUpdate.tailSnippet(
            when (status) {
                is AgentOverlayStatus.GeneratingAnswer -> runDetail
                is AgentOverlayStatus.RunningTool, is AgentOverlayStatus.HostedToolRunning -> runThought
                else -> ""
            },
        )
        // 锁屏自动降级：只显阶段不显内容（隐私优先），解锁后自动恢复。
        val keyguardLocked = getSystemService(android.app.KeyguardManager::class.java)
            ?.isKeyguardLocked == true
        val showOutput = !keyguardLocked && io.github.mangi.eta.config.Prefs.isEnabled(
            io.github.mangi.eta.config.Prefs.Keys.AGENT_NOTIFY_SHOW_OUTPUT,
        )
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.execution_title))
            .setContentText(if (showOutput && snippet.isNotEmpty()) snippet else executionText(this, status, taskCount))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            // 提升请求无条件携带：API 36.1+ 由平台识别；OEM（ColorOS 等）把该行为回移到更低版本时，
            // 版本门会挡住显式 API，但 extra 仍可命中（旧系统对未知 extra 直接忽略）。
            .addExtras(Bundle().apply { putBoolean(Notification.EXTRA_REQUEST_PROMOTED_ONGOING, true) })
            .addAction(Notification.Action.Builder(null, getString(R.string.execution_stop), stop).build())
        // 只有 Agent run 在跑时才申请提升为 Live Update；终端会话、Linux 启动等长驻任务不属于"正在进行、需要持续关注"的活动。
        // 版本判断必须内联：lint 只认调用点的内联检查（抽成函数会丢 NewApi 推断）；
        // SDK_INT >= 36 先短路，旧运行时（含 Robolectric）根本没有 SDK_INT_FULL 字段。
        if (status != null &&
            Build.VERSION.SDK_INT >= 36 &&
            Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.BAKLAVA_1
        ) {
            // 官方语义：setShortCriticalText 空串 = 胶囊不显示文字；短文本缺失时不设置，避免占位。
            val chip = status.shortText().takeIf { it.isNotBlank() }
            // 阶段进度条：思考 → 工具 → 回答 三段，里程碑点标阶段边界。
            // 提升资格条件（ongoing/标题/无 RemoteViews/无 colorized/请求 extra）不变。
            val stageColor = android.graphics.Color.argb(255, 74, 108, 247)
            val pointColor = android.graphics.Color.argb(255, 154, 162, 235)
            val style = Notification.ProgressStyle()
                .setStyledByProgress(true)
                .addProgressSegment(Notification.ProgressStyle.Segment(STAGE_LENGTH).setColor(stageColor))
                .addProgressSegment(Notification.ProgressStyle.Segment(STAGE_LENGTH).setColor(stageColor))
                .addProgressSegment(Notification.ProgressStyle.Segment(STAGE_LENGTH).setColor(stageColor))
                .addProgressPoint(Notification.ProgressStyle.Point(STAGE_LENGTH).setColor(pointColor))
                .addProgressPoint(Notification.ProgressStyle.Point(STAGE_LENGTH * 2).setColor(pointColor))
                .setProgress(status.stageProgress())
            builder
                .setStyle(style)
                .setRequestPromotedOngoing(true)
            if (chip != null) builder.setShortCriticalText(chip)
        }
        val built = builder.build()
        logPromotionDiagnostics(built)
        return built
    }

    /**
     * 实时通知诊断（仅在 API 36.1+ 记录）：区分"我们的通知不合规"与"用户/系统没放行"，
     * 避免下次再靠猜。hasPromotableCharacteristics 只看通知本身；canPostPromotedNotifications
     * 看用户是否在设置里关闭了实时通知。
     */
    private fun logPromotionDiagnostics(notification: Notification) {
        // 内联早退：lint 可推断；SDK_INT < 36 时不会触碰不存在的 SDK_INT_FULL 字段。
        if (Build.VERSION.SDK_INT < 36) return
        if (Build.VERSION.SDK_INT_FULL < Build.VERSION_CODES_FULL.BAKLAVA_1) return
        val promotable = notification.hasPromotableCharacteristics()
        // 36.0 上 canPostPromotedNotifications 可能直接抛异常（生产库实测）；探测绝不能崩。
        val allowed = runCatching {
            getSystemService(NotificationManager::class.java).canPostPromotedNotifications()
        }.getOrDefault(false)
        AndroidAgentLogger.info("Execution notification promotable=$promotable allowed=$allowed")
    }

    /**
     * 阶段进度：三段各 [STAGE_LENGTH]，取段中点表示"正在该阶段"。
     * 思考 → 工具 → 回答；其余状态就近映射，不动进度条长度语义。
     */
    private fun AgentOverlayStatus.stageProgress(): Int = when (this) {
        is AgentOverlayStatus.RunningTool, is AgentOverlayStatus.HostedToolRunning -> STAGE_LENGTH * 3 / 2
        AgentOverlayStatus.GeneratingAnswer -> STAGE_LENGTH * 5 / 2
        else -> STAGE_LENGTH / 2
    }

    /** 状态栏胶囊约 7 个字符可完整显示；更长时只取动作，工具名留在展开态。 */
    private fun AgentOverlayStatus.shortText(): String = when (this) {
        is AgentOverlayStatus.RunningTool, is AgentOverlayStatus.HostedToolRunning -> getString(R.string.execution_chip_running)
        AgentOverlayStatus.Paused -> getString(R.string.overlay_paused)
        AgentOverlayStatus.Stopping -> getString(R.string.overlay_stopping)
        AgentOverlayStatus.GeneratingAnswer -> getString(R.string.execution_chip_answering)
        else -> getString(R.string.overlay_reasoning)
    }

    companion object {
        // v2：重要性从 LOW 提到 DEFAULT（静音），满足实时通知/OEM 流体云的提升门槛；
        // 通道重要性创建后不可修改，必须换 id 才能生效（见 onCreate 的迁移说明）。
        private const val CHANNEL = "eta_execution_v2"
        private const val LEGACY_CHANNEL = "eta_execution"
        private const val NOTIFICATION_ID = 1107
        /** 进度条每阶段长度（三段合计 = 最大进度）。 */
        private const val STAGE_LENGTH = 100
        private const val ACTION_STOP = "io.github.mangi.eta.action.STOP_USER_EXECUTION"
        private val leases = ExecutionLeaseRegistry()
        private val ownerSequence = AtomicLong()
        private val mainHandler = Handler(Looper.getMainLooper())
        @Volatile private var instance: AgentExecutionService? = null
        private var runStatus: AgentOverlayStatus? = null
        private var activeRunCount = 0
        private var publishedStatusText: String? = null
        private var runDetail: String = ""
        private var runThought: String = ""
        private var runConversationId: String = ""

        /**
         * Agent run 的当前状态，供通知与状态栏胶囊展示；null 表示没有 Agent run 在跑。
         * 只在显示文字变化时刷新通知，避免流式事件把通知更新频率推到系统限流。
         */
        fun updateRunStatus(
            context: Context,
            status: AgentOverlayStatus?,
            activeRunCount: Int,
            detail: String = "",
            thought: String = "",
            conversationId: String = "",
        ) {
            mainHandler.post {
                runStatus = status
                this.activeRunCount = activeRunCount
                runDetail = detail
                runThought = thought
                runConversationId = conversationId
                val count = if (activeRunCount > 0) activeRunCount else leases.count()
                // 指纹含片段：流式内容变化也要触发刷新（节流器负责控频）。
                val text = executionText(context, status, count) +
                    "|" + AgentNotificationUpdate.tailSnippet(detail) +
                    "|" + AgentNotificationUpdate.tailSnippet(thought) +
                    "|" + conversationId
                if (text == publishedStatusText) return@post
                publishedStatusText = text
                instance?.takeIf { it.foregroundActive }?.refreshNotification()
            }
        }

        private fun executionText(context: Context, status: AgentOverlayStatus?, taskCount: Int): String {
            val summary = context.resources.getQuantityString(R.plurals.execution_summary, taskCount, taskCount)
            return when {
                status == null -> summary
                taskCount <= 1 -> status.localizedText(context.resources)
                else -> "$summary · ${status.localizedText(context.resources)}"
            }
        }

        /** 必须从有效的用户入口取得引用，再创建会话或子进程；失败时调用方不启动任务。 */
        fun acquire(
            context: Context,
            id: String,
            allowBoundFallback: Boolean = false,
            onStop: () -> Unit,
        ): Boolean {
            if (instance?.startRejected == true) return false
            if (!leases.acquire(id, allowBoundFallback, onStop)) return true
            return try {
                context.applicationContext.startForegroundService(Intent(context, AgentExecutionService::class.java))
                true
            } catch (failure: RuntimeException) {
                leases.release(id)
                AndroidAgentLogger.warn("Execution service start rejected: type=${failure.safeLogType()}")
                false
            }
        }

        fun release(id: String) {
            leases.release(id)
            mainHandler.post { instance?.refreshNotification() }
        }
    }
}
