package io.github.mangi.eta.ui.model

import io.github.mangi.eta.agent.model.AgentModelClient
import org.json.JSONObject

/**
 * 压缩历史投影（W3，context-compaction-v2）。
 *
 * 从会话 history 中的检查点摘要（`contextSummary = true`）投影出时间线条目；
 * 压缩元数据随摘要落库（[AgentModelClient.ConversationMessage.compactionMeta]），
 * 旧数据缺省空串——面板显示「未记录」而不是崩。
 */
internal object AgentContextHistory {

    /** 一次压缩的展示条目。 */
    data class Record(
        /** 第几次压缩（1 起）。 */
        val ordinal: Int,
        /** 对应 history 中的下标（编辑时定位用）。 */
        val historyIndex: Int,
        val summaryText: String,
        /** 本次压缩覆盖的用户轮数。 */
        val coveredUserTurns: Int,
        val tokensBefore: Int?,
        val droppedMessages: Int?,
        val atMillis: Long?,
    )

    fun records(history: List<AgentModelClient.ConversationMessage>): List<Record> {
        var ordinal = 0
        val out = ArrayList<Record>()
        history.forEachIndexed { index, message ->
            if (!message.contextSummary) return@forEachIndexed
            ordinal++
            val meta = parseMeta(message.compactionMeta)
            out += Record(
                ordinal = ordinal,
                historyIndex = index,
                summaryText = message.content,
                coveredUserTurns = message.compactedUserTurns,
                tokensBefore = meta?.optInt("tokensBefore", -1)?.takeIf { it >= 0 },
                droppedMessages = meta?.optInt("dropped", -1)?.takeIf { it >= 0 },
                atMillis = meta?.optLong("at", -1L)?.takeIf { it > 0 },
            )
        }
        return out
    }

    private fun parseMeta(raw: String): JSONObject? {
        if (raw.isBlank()) return null
        return runCatching { JSONObject(raw) }.getOrNull()
    }
}
