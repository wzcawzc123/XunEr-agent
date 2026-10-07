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
        /**
         * 触发压缩的窗口占用比例（0.8，与 DeepSeek Harness 的 `thresholdRatio` 默认值一致）。
         *
         * 阈值取两个约束的较小者：窗口比例，以及「窗口 − 输出预留 − 余量」。
         * 参考实现：deepseek-ai/deepseek-harness `packages/compaction/compaction-basic/src/config.ts`
         * 的 `resolveCompactSpec`（MIT）。
         */
        const val TRIGGER_RATIO = 0.8

        /**
         * 路由请求的输出预留（tokens）。
         *
         * harness 取"生效信封的 maxTokens"；本工程的 [AgentModelClient.ModelConfig] 暂无请求级
         * 输出上限，故取保守常量。若将来引入请求级 maxTokens，应改为读取实际值。
         */
        const val OUTPUT_RESERVE_TOKENS = 16_384

        /** 额外压力余量（tokens），与 harness 默认 65536 一致。 */
        const val HEADROOM_TOKENS = 65_536

        /**
         * 逐字保留的近期历史比例（相对「窗口 − 输出预留」），与 harness 默认 0.16 一致。
         *
         * 保留足够的近期上下文，能让压缩"少而狠"：压完常驻 ≈ retain + 摘要，
         * 之后要重新长到触发线才会再压，压缩次数（以及随之而来的摘要漂移与缓存击穿）都随之下降。
         */
        const val RETAIN_RATIO = 0.16

        /** 触发线的绝对上限；0 表示不设上限（按 harness 行为纯比例）。 */
        const val ABSOLUTE_TRIGGER_CAP = 0

        const val RECENT_MESSAGES = 4

        /** 触发压缩的占用阈值：窗口比例与「窗口 − 输出预留 − 余量」取小；窗口无效时返回 null。 */
        fun triggerTokens(window: Int?): Int? {
            val w = window?.takeIf { it > 0 } ?: return null
            val resolved = minOf((w * TRIGGER_RATIO).toInt(), pressureBudgetTokens(w))
            if (resolved <= 0) return null
            return if (ABSOLUTE_TRIGGER_CAP > 0) minOf(resolved, ABSOLUTE_TRIGGER_CAP) else resolved
        }

        /** 压缩后逐字保留的近期历史预算；窗口无效时返回 null（保留全部由调用方决定）。 */
        fun retainTokens(window: Int?): Int? {
            val w = window?.takeIf { it > 0 } ?: return null
            val messageBudget = w - outputReserveTokens(w)
            if (messageBudget <= 0) return null
            return (messageBudget * RETAIN_RATIO).toInt()
        }

        /**
         * 输出预留：小窗口下按比例收缩。
         *
         * harness 在加载期直接报配置错误（要求「窗口 − 输出预留 − 余量」为正）；Eta 允许用户
         * 给任意模型填任意窗口，故按比例退化，保证小窗口模型仍能自动压缩。
         */
        fun outputReserveTokens(window: Int): Int = minOf(OUTPUT_RESERVE_TOKENS, window / 16)

        /** 额外压力余量：同样按窗口收缩。 */
        fun headroomTokens(window: Int): Int = minOf(HEADROOM_TOKENS, window / 8)

        private fun pressureBudgetTokens(window: Int): Int =
            window - outputReserveTokens(window) - headroomTokens(window)

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
