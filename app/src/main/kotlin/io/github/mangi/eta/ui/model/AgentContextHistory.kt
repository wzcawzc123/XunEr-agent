package io.github.mangi.eta.ui.model

import io.github.mangi.eta.agent.model.provider.AgentModelClient

/**
 * 压缩历史投影：从会话 history 里的摘要消息（`contextSummary = true`）还原
 * 「每次压缩到底发生了什么」。
 *
 * ## 为什么不另存元数据
 *
 * 早先的设计是在摘要消息上挂一份 `compactionMeta`（时间戳、压缩前 token、丢弃条数）。
 * 那要动数据库 schema——为几个诊断字段做一次迁移，收益不成比例。
 * 摘要消息本身已经带着诊断需要的全部信息：
 *
 * - 卡片正文 = 模型实际拿到的交接卡（能看出哪几段被填成了「无」）；
 * - `compactedUserTurns` \/ `summaryThroughUserTurn` = 这次压缩覆盖到第几轮；
 * - `compactionArchiveId`（`fold-N`）= 代数，也就是这是第几次压缩。
 *
 * 时间与 token 数缺失只影响「看一眼大概」的体验，不影响定位问题。
 *
 * ## 条数 ≠ 次数
 *
 * 每次压缩都会把旧摘要卡折进新摘要后丢掉，history 里始终只留最新一张卡。
 * 因此「列表里有几张卡」只说明还留着几份正文，压缩次数要看归档号：
 * 只有一张 `fold-5` 卡，说明已经压过 5 次，前 4 张的正文已被吸收。
 * 用 [total] 取次数，不要用 `records.size`。
 */
internal object AgentContextHistory {

    /** 归档号形如 `fold-5`，其中的数字就是真实压缩次数。 */
    private val ARCHIVE_SEQ = Regex("""fold-(\d+)""")

    data class Record(
        /** 第几次压缩（1 起），优先取自归档号；归档号缺失时退化为卡片的出现顺序。 */
        val ordinal: Int,
        /** 在 history 里的下标（将来做跳转\/编辑定位用）。 */
        val historyIndex: Int,
        /** 本次压缩覆盖的用户轮数。 */
        val coveredUserTurns: Int,
        /** 这张卡覆盖到第几个用户轮（含被保护未压缩的那条）。 */
        val summaryThroughUserTurn: Int,
        /** 交接卡正文。 */
        val summaryText: String,
        /** 归档号（`fold-N`），历史遗留数据可能为空。 */
        val archiveId: String = "",
    )

    fun records(history: List<AgentModelClient.ConversationMessage>): List<Record> {
        var fallback = 0
        val out = ArrayList<Record>()
        history.forEachIndexed { index, message ->
            if (!message.contextSummary) return@forEachIndexed
            fallback++
            val archiveId = message.compactionArchiveId
            val seq = ARCHIVE_SEQ.find(archiveId)?.groupValues?.getOrNull(1)?.toIntOrNull()
            out += Record(
                ordinal = seq?.takeIf { it > 0 } ?: fallback,
                historyIndex = index,
                coveredUserTurns = message.compactedUserTurns,
                summaryThroughUserTurn = message.summaryThroughUserTurn,
                summaryText = message.content,
                archiveId = archiveId,
            )
        }
        return out
    }

    /**
     * 真实压缩次数：最新一张卡的代数。
     *
     * 早期卡片的正文已被吸收进新摘要，但代数留在了归档号里，所以次数不能被
     * `records.size` 截断——否则压过 5 次也只显示「共 1 次」。
     */
    fun total(records: List<Record>): Int = records.maxOfOrNull { it.ordinal } ?: 0
}
