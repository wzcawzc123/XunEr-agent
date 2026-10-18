package io.github.mangi.eta.agent.autotask

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 把"待执行队列"导出成一个纯文本文件，供 root 守护脚本读取。
 *
 * **为什么需要这个文件**：本机没有 `sqlite3`，守护脚本读不了 Room 数据库。
 * 而 App（普通 uid）也写不了 `/data/local/tmp`（root 目录）。
 * 折中方案：App 写自己的私有目录（有权限），守护脚本以 root 身份读它。
 *
 * **为什么用文本而不是 JSON**：shell 里解析 JSON 要么依赖 jq（本机没有），
 * 要么手写正则（易错）。用 `|` 分隔的一行一条，`IFS='|' read` 一行搞定。
 *
 * 格式（每行一条，字段顺序固定）：
 * ```
 * id|title|nextRunAtMillis|needCharging|needWifi|needScreenOff|conversationId
 * ```
 * - `nextRunAtMillis`：0 表示"无时间限制，条件满足即可"
 * - 三个 need*：1/0
 * - `conversationId`：投递目标会话；空表示退化为临时会话
 *
 * 设计约束：
 * - **写文件失败绝不影响主流程** —— 任务本身仍能保存进数据库，只是守护脚本看不到它；
 * - 原子写（先写 .tmp 再 rename），避免脚本读到半截文件。
 */
internal object AutoTaskExport {

    private const val DIR_NAME = "autotask"
    private const val FILE_NAME = "pending.txt"

    fun pendingFile(context: Context): File =
        File(File(context.filesDir, DIR_NAME), FILE_NAME)

    /** 导出当前所有**可执行**任务（enabled + active）。 */
    fun export(context: Context, tasks: List<AgentTask>, nowMillis: Long) {
        runCatching {
            val runnable = tasks.filter { it.enabled && it.active }
            val body = buildString {
                appendLine("# 由 Eta 自动生成，勿手改")
                appendLine("# 导出时间: ${formatTime(nowMillis)}")
                appendLine("# 格式: id|title|nextRunAtMillis|needCharging|needWifi|needScreenOff|conversationId")
                for (task in runnable) {
                    val next = task.schedule?.nextRunAtMillis(nowMillis, task.lastRunAt) ?: 0L
                    val c = task.condition
                    append(task.id).append('|')
                    append(task.title.replace('|', '/')).append('|')  // 标题里的竖线会破坏格式
                    append(next).append('|')
                    append(if (c.requireCharging) 1 else 0).append('|')
                    append(if (c.requireWifi) 1 else 0).append('|')
                    append(if (c.requireScreenOff) 1 else 0).append('|')
                    append(task.conversationId ?: "")
                    appendLine()
                }
            }
            val target = pendingFile(context)
            val tmp = File(target.parentFile, "$FILE_NAME.tmp")
            target.parentFile?.mkdirs()
            tmp.writeText(body)
            // 原子替换：脚本要么看到旧版、要么看到新版，不会读到半截
            if (!tmp.renameTo(target)) {
                target.writeText(body)
                tmp.delete()
            }
        }
    }

    /** 供日志用：导出失败时静默（调用方无需感知）。 */
    private fun formatTime(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(millis))
}
