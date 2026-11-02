package io.github.mangi.eta.ui.model

import io.github.mangi.eta.agent.model.AgentModelClient

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
 * - `compactedUserTurns` / `summaryThroughUserTurn` = 这次压缩覆盖到第几轮；
 * - 顺序 = 代数（第几次压缩）。
 *
 * 时间与 token 数缺失只影响「看一眼大概」的体验，不影响定位问题。
 */
internal object AgentContextHistory {

    data class Record(
        /** 第几次压缩（1 起），同时就是代数。 */
        val ordinal: Int,
        /** 在 history 里的下标（将来做跳转/编辑定位用）。 */
        val historyIndex: Int,
        /** 本次压缩覆盖的用户轮数。 */
        val coveredUserTurns: Int,
        /** 这张卡覆盖到第几个用户轮（含被保护未压缩的那条）。 */
        val summaryThroughUserTurn: Int,
        /** 交接卡正文。 */
        val summaryText: String,
    )

    fun records(history: List<AgentModelClient.ConversationMessage>): List<Record> {
        var ordinal = 0
        val out = ArrayList<Record>()
        history.forEachIndexed { index, message ->
            if (!message.contextSummary) return@forEachIndexed
            ordinal++
            out += Record(
                ordinal = ordinal,
                historyIndex = index,
                coveredUserTurns = message.compactedUserTurns,
                summaryThroughUserTurn = message.summaryThroughUserTurn,
                summaryText = message.content,
            )
        }
        return out
    }
}
