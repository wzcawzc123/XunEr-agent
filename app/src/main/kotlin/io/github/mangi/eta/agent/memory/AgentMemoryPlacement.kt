package io.github.mangi.eta.agent.memory

/**
 * 写入时的**归档位置建议**。
 *
 * 为什么只"建议"不"自动执行"：MEMORY.md 是用户资产，位置错了代价很高
 * （放进核心会让 32K 预算被占，放进正文又可能永远不被读到）。
 * 建议 + 理由，让模型/用户自己拍板 —— 这是「有意见但不越权」的边界。
 *
 * 判定基于关键词而非模型推断：纯函数、零成本、可解释，且能被单测钉死。
 * 关键词来自本项目 MEMORY.md 的既有语言习惯（"用户指令""结项""收官"等）。
 */
internal enum class AgentMemoryPlacement(val wire: String, val label: String) {
    /** 每轮都会注入，预算有限，只放"永远要用"的东西。 */
    CORE("core", "核心记忆"),

    /** 已结束的项目档案，按需检索即可。 */
    ARCHIVE("archive", "归档"),

    /** 其余长期背景，保留在正文。 */
    BODY("body", "正文"),
    ;

    companion object {
        /** 明确表达"这是长期规则"的词。命中即建议核心。 */
        private val CORE_MARKERS = listOf(
            "用户指令", "长期执行", "铁律", "规范", "必须", "禁止", "始终", "一律",
            "流程", "约定",
        )

        /** 明确表达"这件事已经结束"的词。命中即建议归档。 */
        private val ARCHIVE_MARKERS = listOf(
            "结项", "收官", "终审", "定案", "已交付", "已发布", "已上线", "结案",
            "已完成", "闭环", "完结",
        )

        /**
         * 给一段待写入内容建议位置。
         *
         * 优先级：**归档 > 核心**。
         * 理由：一段文字如果既说"这是规范"又说"已结项"，它更是历史记录 ——
         * 把已结束的规范留在核心只会占预算。
         *
         * @return 建议位置 + 一句可读理由（返回给模型，让它知道为什么）
         */
        fun suggest(content: String): Suggestion {
            val text = content.trim()
            if (text.isEmpty()) return Suggestion(BODY, "内容为空，无需特别处理")

            val archiveHit = ARCHIVE_MARKERS.firstOrNull { text.contains(it) }
            if (archiveHit != null) {
                return Suggestion(ARCHIVE, "命中「$archiveHit」，读起来是已结束的记录，建议归档")
            }

            val coreHit = CORE_MARKERS.firstOrNull { text.contains(it) }
            if (coreHit != null) {
                return Suggestion(CORE, "命中「$coreHit」，像是长期规则，可考虑放核心（注意 32K 预算）")
            }

            return Suggestion(BODY, "未命中规则/结案特征，按正文保存即可")
        }
    }

    data class Suggestion(val placement: AgentMemoryPlacement, val reason: String)
}
