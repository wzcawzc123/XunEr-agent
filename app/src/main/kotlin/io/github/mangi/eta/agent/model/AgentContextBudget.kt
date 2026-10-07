package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentTokenUsage
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ceil

/** usage 只校准同一模型的请求估算，不把累计计费用量当作窗口占用。 */
internal class AgentContextBudget(private val window: Int?) {
    private var calibration = 1.0

    fun observe(usage: AgentTokenUsage?, requestEstimate: Int) {
        val input = usage?.inputTokens ?: usage?.contextTokens ?: return
        if (input > 0 && requestEstimate > 0) {
            calibration = (input.toDouble() / requestEstimate).coerceIn(1.0, 8.0)
        }
    }

    fun estimate(messages: JSONArray, tools: JSONArray): Int =
        ceil(rawEstimate(messages, tools) * calibration).toInt()

    fun shouldCompact(tokens: Int): Boolean = triggerTokens(window)?.let { tokens >= it } == true

    fun exceedsWindow(tokens: Int): Boolean = window?.takeIf { it > 0 }?.let { tokens >= it } == true

    companion object {
        /** 触发压缩的窗口占用比例。从 0.85 降到 0.75，为系统提示、工具 schema 和本轮增长留出 25% 安全余量。 */
        const val TRIGGER_RATIO = 0.75

        /**
         * 触发线的绝对上限（tokens）。
         *
         * 大窗口模型（100 万级）按比例算出的触发线会让单轮成本失控：真机实测
         * （2026-10-07，会话 conv-3d2f1ce4）在 75 万触发线下单轮 input 达 749,916 tokens，
         * 且压缩会改写历史前缀触发缓存击穿（该轮缓存命中率从 99.9% 掉到 9.4%，等于全价重算）。
         * 上限让触发线不随窗口无限放大：窗口越大，越不能把「还装得下」当成「应该装满」。
         */
        const val ABSOLUTE_TRIGGER_CAP = 200_000
        const val RECENT_MESSAGES = 4
        const val RECENT_RATIO = 0.20
        const val MAX_OVERFLOW_ATTEMPTS = 3

        /** 触发压缩的占用阈值：窗口比例与绝对上限取小；窗口无效时返回 null（不触发）。 */
        fun triggerTokens(window: Int?): Int? = window?.takeIf { it > 0 }?.let {
            minOf((it * TRIGGER_RATIO).toInt(), ABSOLUTE_TRIGGER_CAP)
        }

        fun textTokens(text: String): Int {
            var ascii = 0
            var other = 0
            text.codePoints().forEach { if (it < 128) ascii++ else other++ }
            return (ascii + 2) / 3 + other
        }

        fun rawEstimate(messages: JSONArray, tools: JSONArray = JSONArray()): Int {
            var tokens = textTokens(tools.toString()) + 16
            for (index in 0 until messages.length()) {
                val message = messages.optJSONObject(index) ?: continue
                val copy = JSONObject()
                message.keys().forEach { key -> if (key != "content") copy.put(key, message.get(key)) }
                val parts = message.optJSONArray("content")
                if (parts != null) {
                    val text = JSONArray()
                    for (partIndex in 0 until parts.length()) {
                        val part = parts.optJSONObject(partIndex) ?: continue
                        if (part.optString("type") in setOf("image_url", "input_image", "image")) {
                            tokens += 4096
                        } else text.put(part)
                    }
                    copy.put("content", text)
                } else copy.put("content", message.opt("content"))
                tokens += textTokens(copy.toString()) + 8
            }
            return tokens
        }
    }
}
