package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * 工具结果修剪器：确定性、零模型调用的上下文瘦身。
 *
 * 设计参照 deepseek-harness 的 `compaction-tool-result-pruner`（MIT）：超预算的工具结果只保留
 * 头尾，中间用固定标记替换；预算取上游默认值（阈值 8192 / 头 4096 / 尾 1024 字符）。
 *
 * 与上游实现的三点差异（本机适配，均有测试锁定）：
 * 1. 本机多数工具返回 JSON 字符串，直接按文本切会把结构切坏、模型无法解析；
 *    因此先尝试 JSON 解析，成功则对超预算的字符串叶子逐字段修剪，保证结果仍可解析；
 *    解析失败按纯文本修剪。
 * 2. 不修改会话持久历史：本修剪器只作用于每轮构造出的出站请求消息（AgentLoop 调用点），
 *    数据库里的原文与 UI 展示不受影响。
 * 3. 幂等：已修剪内容必然落在阈值内，重复执行不再变化，因此每轮对同一历史修剪得到的
 *    前缀完全一致 —— 这是不牺牲缓存命中率的前提。
 *
 * 预算语义：修剪后的长度 = 头 + 标记 + 尾，恒小于阈值（由 [resolve] 校验）。
 */
object AgentToolResultPruner {

    /**
     * 超过该字符数才修剪。
     *
     * 上游默认 8192，那是按 coding agent 的 bash 巨型输出（动辄数十万字符）定的。
     * 本机实测（878 条真实工具结果）：中位数 556 字符、p90 仅 1593、最大 16846，
     * 8192 只会命中 7 条、省 6.5%；2048 命中 57 条、省 26.3%，是收益与信息保留的平衡点。
     */
    const val THRESHOLD_CHARS: Int = 2_048

    /** 保留头部字符数（上游 4096 → 本机按总预算等比取 1/2）。 */
    const val HEAD_CHARS: Int = 1_024

    /** 保留尾部字符数（上游 1024 → 本机按总预算等比取 1/8）。 */
    const val TAIL_CHARS: Int = 256

    /** 被移除中段的固定替换标记（上游同语义，措辞本地化为中文）。 */
    const val MARKER: String = "\n\n[... 中间内容已修剪 ...]\n\n"

    /**
     * 文本是否超出预算。
     *
     * @param text 待检查文本
     * @return 超预算返回 true
     */
    fun isOverBudget(text: String): Boolean = text.length > THRESHOLD_CHARS

    /**
     * 按预算修剪一段工具结果。
     *
     * @param text 工具结果原文
     * @return 修剪后文本；未超预算时原样返回（引用相同）
     */
    fun prune(text: String): String {
        if (!isOverBudget(text)) return text
        runCatching { JSONObject(text) }.getOrNull()?.let { return pruneJsonValue(it).toString() }
        runCatching { JSONArray(text) }.getOrNull()?.let { return pruneJsonValue(it).toString() }
        return prunePlainText(text)
    }

    /**
     * 修剪出站请求消息数组中的工具结果内容。
     *
     * 只作用于发给模型的请求副本，不修改会话持久历史（数据库与 UI 展示保持原文）。
     * 没有需要修剪的消息时返回**原引用**，避免每轮重建数组、保证前缀稳定。
     *
     * @param messages 出站请求消息数组
     * @return 修剪后的数组，或原引用
     */
    fun pruneRequestMessages(messages: JSONArray): JSONArray {
        var changed = false
        val prunedMessages = JSONArray()
        for (index in 0 until messages.length()) {
            val message = messages.optJSONObject(index)
            if (message == null || message.optString("role") != "tool") {
                prunedMessages.put(messages.get(index))
                continue
            }
            val content = message.optString("content")
            val pruned = if (isOverBudget(content)) prune(content) else content
            if (pruned == content) {
                prunedMessages.put(message)
                continue
            }
            prunedMessages.put(JSONObject(message.toString()).put("content", pruned))
            changed = true
        }
        return if (changed) prunedMessages else messages
    }

    /**
     * 纯文本修剪：保留头尾，中段替换为标记。
     *
     * @param text 超预算文本
     * @return 修剪后文本
     */
    private fun prunePlainText(text: String): String {
        if (text.length <= HEAD_CHARS + TAIL_CHARS + MARKER.length) return pruneByRatio(text)
        return safeHead(text, HEAD_CHARS) + MARKER + safeTail(text, TAIL_CHARS)
    }

    /**
     * 极短阈值下的兜底：按比例头尾二分，仍保证结果更小。
     *
     * @param text 超预算文本
     * @return 修剪后文本
     */
    private fun pruneByRatio(text: String): String {
        val half = (text.length / 2).coerceAtLeast(1)
        val keep = ((text.length - MARKER.length - half) / 2).coerceAtLeast(0)
        return safeHead(text, keep) + MARKER + safeTail(text, keep)
    }

    /**
     * 递归修剪 JSON 结构中的超预算字符串叶子，保持结构可解析。
     *
     * @param value JSON 值（对象 / 数组 / 标量）
     * @return 修剪后的同构值
     */
    private fun pruneJsonValue(value: Any?): Any? = when (value) {
        is String -> pruneJsonProperty(value)
        is JSONObject -> JSONObject().also { target ->
            for (key in value.keys()) {
                val child = value.opt(key)
                if (child == null || child === JSONObject.NULL) {
                    target.put(key, JSONObject.NULL)
                } else {
                    target.put(key, pruneJsonValue(child))
                }
            }
        }
        is JSONArray -> JSONArray().also { target ->
            for (index in 0 until value.length()) {
                val child = value.opt(index)
                if (child == null || child === JSONObject.NULL) {
                    target.put(JSONObject.NULL)
                } else {
                    target.put(pruneJsonValue(child))
                }
            }
        }
        else -> value
    }

    /**
     * 修剪单个 JSON 字符串字段值。
     *
     * @param value 字段原文
     * @return 未超预算时原样返回，否则头尾保留
     */
    private fun pruneJsonProperty(value: String): String {
        if (!isOverBudget(value)) return value
        if (value.length <= HEAD_CHARS + TAIL_CHARS + MARKER.length) return value
        return safeHead(value, HEAD_CHARS) + MARKER + safeTail(value, TAIL_CHARS)
    }

    /**
     * 取头部若干字符，且不切裂代理对（emoji 等）。
     *
     * @param text 源文本
     * @param chars 期望字符数
     * @return 头片段
     */
    private fun safeHead(text: String, chars: Int): String {
        val end = minOf(chars, text.length)
        if (end <= 0) return ""
        if (end < text.length && Character.isHighSurrogate(text[end - 1])) {
            return text.substring(0, end - 1)
        }
        return text.substring(0, end)
    }

    /**
     * 取尾部若干字符，且不切裂代理对。
     *
     * @param text 源文本
     * @param chars 期望字符数
     * @return 尾片段
     */
    private fun safeTail(text: String, chars: Int): String {
        val start = text.length - chars
        if (start >= text.length) return ""
        if (start <= 0) return text
        if (Character.isLowSurrogate(text[start])) {
            return text.substring(start + 1)
        }
        return text.substring(start)
    }
}
