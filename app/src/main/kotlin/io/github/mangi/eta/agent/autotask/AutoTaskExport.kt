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
            // 过滤掉已完结的一次性任务：它们不会再执行，留在文件里只会让脚本
            // 每分钟白唤醒一次 App（真机实测：25 秒内被唤醒两次）。
            val runnable = tasks.filter { it.enabled && it.active && !it.isFinished }
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


    private const val RUN_FILE_NAME = "pending_run.txt"

    /** 守护脚本写的「待执行标记」文件（App 私有目录，双方都可读写）。 */
    fun runMarkerFile(context: Context): File = File(File(context.filesDir, DIR_NAME), RUN_FILE_NAME)

    // ---------- App 侧「执行中」标记（崩溃恢复用） ----------
    //
    // 与脚本的 pending_run.txt 是两套东西：这里记录「App 正在离线执行哪条任务」，
    // 进程中途被杀（系统回收/崩溃）后，标记残留；下次唤醒据此恢复。
    // 语义：任何残留标记都只可能来自死进程（活着时同进程内不会并发二次进入）。

    private const val IN_PROGRESS_FILE_NAME = "in_progress.txt"

    private fun inProgressFile(context: Context): File = File(File(context.filesDir, DIR_NAME), IN_PROGRESS_FILE_NAME)

    /** 离线轮开始前写入执行中标记（taskId|startedAt）。写失败绝不影响执行。 */
    fun markInProgress(context: Context, taskId: String, startedAt: Long) {
        runCatching {
            val file = inProgressFile(context)
            file.parentFile?.mkdirs()
            val kept = file.takeIf { it.exists() }?.readLines()?.filterNot { it.startsWith("$taskId|") }
                ?: emptyList()
            writeLinesAtomically(file, kept + "$taskId|$startedAt")
        }
    }

    /** 离线轮完成后清除执行中标记。 */
    fun clearInProgress(context: Context, taskId: String) {
        runCatching {
            val file = inProgressFile(context)
            if (!file.exists()) return
            val kept = file.readLines().filterNot { it.startsWith("$taskId|") }
            writeLinesAtomically(file, kept)
        }
    }

    /** 读取全部执行中标记：taskId -> startedAt（用于崩溃恢复；不存在则空）。 */
    fun inProgressEntries(context: Context): Map<String, Long> {
        val file = inProgressFile(context)
        if (!file.exists()) return emptyMap()
        return runCatching {
            file.readLines().mapNotNull { line ->
                val idx = line.indexOf('|')
                if (idx <= 0) return@mapNotNull null
                val startedAt = line.substring(idx + 1).toLongOrNull() ?: return@mapNotNull null
                line.substring(0, idx) to startedAt
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    private fun writeLinesAtomically(target: File, lines: List<String>) {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        val body = if (lines.isEmpty()) "" else lines.joinToString("\n") + "\n"
        tmp.writeText(body)
        if (!tmp.renameTo(target)) {
            target.writeText(body)
            tmp.delete()
        }
    }

    /** 供日志用：导出失败时静默（调用方无需感知）。 */
    private fun formatTime(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(millis))
}
