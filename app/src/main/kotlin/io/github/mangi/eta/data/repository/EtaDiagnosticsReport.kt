package io.github.mangi.eta.data.repository

import android.content.Context
import android.os.Build
import io.github.mangi.eta.agent.model.AgentSensitiveToolPolicy
import io.github.mangi.eta.data.db.EtaDatabase
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * M3.3 结构化诊断导出。
 *
 * 数据源 = `conversations`（updated_at DESC）+ `conversation_messages`（全量）——
 * **刻意不用 runtime_archive_runs**：那里 `handoff ?: return` 只存语音等 handoff 运行，
 * 会漏掉 99% 普通 run（2026-10-04 审查结论）。
 *
 * 隐私边界（按 M1.4 单一真源）：敏感工具（[AgentSensitiveToolPolicy]）的参数/结果
 * 预览整段替换为占位说明；不导出 API Key、记忆原文、敏感原文。
 * 口径如实：上下文事件只导**有持久记录**的 system_notice（compaction/retry/trimmed…）；
 * 硬裁剪只影响出站请求、无持久标记，报告不谎报。
 */
internal object EtaDiagnosticsReport {

    data class Row(
        val sortIndex: Int,
        val type: String,
        val toolName: String? = null,
        val toolStatus: String? = null,
        val argumentsSummary: String? = null,
        val resultSummary: String? = null,
        val content: String? = null,
    )

    data class Conversation(
        val title: String,
        val updatedAt: Long,
        val messageCount: Int,
        val rows: List<Row>,
        val rowsTruncated: Boolean = false,
    )

    private const val REDACTED = "[敏感工具，已脱敏]"
    private const val MAX_ROWS_PER_CONVERSATION = 1_000
    private const val PREVIEW_LIMIT = 240

    fun build(
        generatedAt: Long,
        appVersion: String,
        deviceInfo: String,
        conversations: List<Conversation>,
    ): String {
        val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
        val out = StringBuilder()
        out.append("# Eta diagnostics\n")
        out.append("generated: ${timeFormat.format(Date(generatedAt))}\n")
        out.append("app: $appVersion\n")
        out.append("device: $deviceInfo\n")
        out.append("note: 敏感工具参数/结果已按策略脱敏；上下文事件仅含持久化标记（硬裁剪无持久记录）\n")

        val codeTally = sortedMapOf<String, Int>()
        val noticeTally = sortedMapOf<String, Int>()

        conversations.forEach { conv ->
            out.append("\n## [${timeFormat.format(Date(conv.updatedAt))}] ${conv.title}\n")
            out.append(
                "messages=${conv.messageCount}" +
                    (if (conv.rowsTruncated) " (仅分析前 $MAX_ROWS_PER_CONVERSATION 条)" else "") +
                    "\n",
            )
            conv.rows.forEach { row ->
                when (row.type) {
                    "tool" -> {
                        val name = row.toolName.orEmpty()
                        val sensitive = AgentSensitiveToolPolicy.isSensitive(name)
                        val args = if (sensitive) REDACTED else preview(row.argumentsSummary)
                        val result = if (sensitive) REDACTED else preview(row.resultSummary)
                        out.append(
                            "#${row.sortIndex} TOOL $name ${row.toolStatus ?: "-"} | $args | $result\n",
                        )
                        if (row.toolStatus.equals("Failed", ignoreCase = true)) {
                            Regex("code=([A-Z][A-Z0-9_]+)")
                                .findAll(row.resultSummary.orEmpty())
                                .forEach { m -> codeTally.merge(m.groupValues[1], 1, Int::plus) }
                        }
                    }
                    "system_notice" -> {
                        val code = row.content.orEmpty().ifBlank { "unknown" }
                        noticeTally.merge(code, 1, Int::plus)
                        out.append("#${row.sortIndex} NOTICE $code | ${preview(row.resultSummary)}\n")
                    }
                }
            }
        }

        out.append("\n== 错误汇总（tool Failed 的 code）==\n")
        if (codeTally.isEmpty()) out.append("(无)\n")
        codeTally.forEach { (code, count) -> out.append("$code × $count\n") }

        out.append("\n== 系统事件汇总（持久化标记）==\n")
        if (noticeTally.isEmpty()) out.append("(无)\n")
        noticeTally.forEach { (code, count) -> out.append("$code × $count\n") }

        return out.toString()
    }

    private fun preview(text: String?): String =
        text?.replace('\n', ' ')?.take(PREVIEW_LIMIT)?.ifBlank { "-" } ?: "-"

    /** 加载近 [conversationLimit] 个会话的工具/系统事件行并写出 UTF-8 文本。 */
    suspend fun export(
        context: Context,
        output: OutputStream,
        conversationLimit: Int = 12,
    ) {
        val dao = EtaDatabase.get(context).conversationDao()
        val metas = dao.conversationsPage(limit = conversationLimit, offset = 0)
        val conversations = metas.map { meta ->
            val total = dao.messageCount(meta.id)
            val rows = dao.messagesPage(meta.id, MAX_ROWS_PER_CONVERSATION, 0)
            Conversation(
                title = meta.title.ifBlank { "(无标题)" },
                updatedAt = meta.updatedAt,
                messageCount = total,
                rows = rows.map { entity ->
                    Row(
                        sortIndex = entity.sortIndex,
                        type = entity.type,
                        toolName = entity.toolName,
                        toolStatus = entity.toolStatus,
                        argumentsSummary = entity.argumentsSummary,
                        resultSummary = entity.resultSummary,
                        content = entity.content,
                    )
                }.filter { it.type == "tool" || it.type == "system_notice" },
                rowsTruncated = total > MAX_ROWS_PER_CONVERSATION,
            )
        }
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "unknown"
        val device = "${Build.MANUFACTURER} ${Build.MODEL} / SDK ${Build.VERSION.SDK_INT}"
        val text = build(
            generatedAt = System.currentTimeMillis(),
            appVersion = version,
            deviceInfo = device,
            conversations = conversations,
        )
        output.write(text.toByteArray(Charsets.UTF_8))
    }
}
