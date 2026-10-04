package io.github.mangi.eta.agent.tool

/**
 * `locate_on_screen` 的纯匹配核心：在 UI 节点快照里按文本定位目标 bbox。
 *
 * 背景（2026-10-04 真机取证，conv-e7b0a537）：稀疏树场景（Compose 列表未暴露子项）
 * 下模型只能目测截图坐标，实测系统性偏 ~0.96（4 次未命中，最终靠裁剪放大才命中）。
 * 本类把“定位”变成程序可判定的查表问题：输入节点快照与 query，输出 screen 坐标 bbox。
 * 纯函数、无 Android 依赖，便于本地（非 Robolectric）单测。
 */
internal object ScreenLocator {

    data class Candidate(
        val index: Int,
        val text: String,
        val desc: String,
        val viewId: String,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val clickable: Boolean,
    )

    data class Match(
        val index: Int,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val centerX: Int,
        val centerY: Int,
        val score: Double,
        val field: String,
        val clickable: Boolean,
    )

    private const val EQUAL_VIEW_ID = 1.00
    private const val EQUAL_TEXT = 0.98
    private const val EQUAL_DESC = 0.96
    private const val CONTAINS_VIEW_ID = 0.85
    private const val CONTAINS_TEXT = 0.80
    private const val CONTAINS_DESC = 0.75
    private const val DEFAULT_LIMIT = 8

    /**
     * 多词 AND 匹配：query 按空白切成 token，每个 token 都必须命中
     * text/desc/viewId 之一，得分取各 token 最优档位的均值。
     * 空 query、空节点、空 bbox 均返回空列表（调用方转 LOCATE_MISS）。
     */
    fun locate(candidates: List<Candidate>, query: String, limit: Int = DEFAULT_LIMIT): List<Match> {
        val tokens = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return emptyList()
        return candidates.asSequence()
            .filter { it.right > it.left && it.bottom > it.top }
            .mapNotNull { candidate -> score(candidate, tokens)?.let { candidate to it } }
            .sortedWith(compareByDescending<Pair<Candidate, Scored>> { it.second.score }.thenBy { it.first.index })
            .take(limit.coerceIn(1, 32))
            .map { (candidate, scored) ->
                Match(
                    index = candidate.index,
                    left = candidate.left,
                    top = candidate.top,
                    right = candidate.right,
                    bottom = candidate.bottom,
                    centerX = (candidate.left + candidate.right) / 2,
                    centerY = (candidate.top + candidate.bottom) / 2,
                    score = scored.score,
                    field = scored.field,
                    clickable = candidate.clickable,
                )
            }
            .toList()
    }

    private data class Scored(val score: Double, val field: String)

    private fun score(candidate: Candidate, tokens: List<String>): Scored? {
        var total = 0.0
        var firstField = ""
        tokens.forEach { token ->
            val best = bestFieldScore(candidate, token) ?: return null
            total += best.score
            if (firstField.isEmpty()) firstField = best.field
        }
        return Scored(total / tokens.size, firstField.ifEmpty { "text" })
    }

    private fun bestFieldScore(candidate: Candidate, token: String): Scored? {
        val viewIdLeaf = candidate.viewId.substringAfterLast('/').lowercase()
        val text = candidate.text.lowercase()
        val desc = candidate.desc.lowercase()
        val options = buildList {
            if (viewIdLeaf.isNotEmpty()) {
                if (viewIdLeaf == token) add(Scored(EQUAL_VIEW_ID, "viewId"))
                else if (viewIdLeaf.contains(token)) add(Scored(CONTAINS_VIEW_ID, "viewId"))
            }
            if (text.isNotEmpty()) {
                if (text == token) add(Scored(EQUAL_TEXT, "text"))
                else if (text.contains(token)) add(Scored(CONTAINS_TEXT, "text"))
            }
            if (desc.isNotEmpty()) {
                if (desc == token) add(Scored(EQUAL_DESC, "desc"))
                else if (desc.contains(token)) add(Scored(CONTAINS_DESC, "desc"))
            }
        }
        return options.maxByOrNull { it.score }
    }
}
