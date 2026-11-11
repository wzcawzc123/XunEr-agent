package io.github.mangi.eta.agent.model

import org.json.JSONArray

/**
 * L3 防漂移 · 锚点与代数。
 *
 * 依据：Amp 的教训（递归摘要会天然衰减）+ Anthropic「用户说的话最准确」。
 * 做法：从上一代交接卡里抽取「用户约束」「关键标识」两段**原文**，要求下一代的摘要
 * 原样保留，不让它们被反复归纳掉；并记录代数，第三代起提醒模型早期细节可能已丢失。
 */
internal object AgentContextAnchors {

    private val ANCHOR_HEADERS = listOf("## 用户约束", "## 关键标识")

    /** 从上一代交接卡抽取必须原样保留的锚点段；空白或缺段返回空串。 */
    fun extract(card: String): String = buildString {
        ANCHOR_HEADERS.forEach { header ->
            val section = sectionOf(card, header)
            if (section.isNotBlank()) {
                append(section).append("\n\n")
            }
        }
    }.trim()

    /** 取某个 `## 标题` 段到下一个 `## 标题` 之间的原文。 */
    private fun sectionOf(text: String, header: String): String {
        val start = text.indexOf(header)
        if (start < 0) return ""
        val next = text.indexOf("\n## ", start + header.length)
        val end = if (next < 0) text.length else next
        return text.substring(start, end).trim()
    }

    /** 新一代交接卡的代数：已有摘要卡数量 + 1。 */
    fun generationOf(messages: JSONArray, systemCount: Int): Int =
        (systemCount until messages.length()).count {
            messages.getJSONObject(it).optBoolean("_eta_context_summary")
        } + 1
}
