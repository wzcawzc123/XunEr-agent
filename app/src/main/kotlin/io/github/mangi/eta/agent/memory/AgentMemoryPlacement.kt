package io.github.mangi.eta.agent.memory

/**
 * 写入时的**归档位置建议**。
 *
 * ## 为什么只建议、不自动执行
 *
 * MEMORY.md 是用户资产，位置错了代价很高：放进核心区会长期占用每个请求的注入预算，
 * 放进正文又可能永远不被读到。这个判断依赖领域知识（"这条还会用多久"），
 * 让模型/用户自己拍板比代码替他决定更安全 —— 这是「有意见但不越权」的边界。
 *
 * ## 为什么用关键词而不是模型推断
 *
 * 纯函数、零成本、可解释、能被单测钉死。关键词取自本项目 MEMORY.md 的既有写作习惯
 * （"结项""收官""已归档"），对本项目语料足够准；判断错了也只是建议错，不是数据错。
 */
internal enum class AgentMemoryPlacement(val wire: String, val label: String) {
    /** 每轮注入，预算有限，只放"永远要用"的东西。 */
    CORE("core", "核心记忆"),

    /** 已结束的项目档案，按需检索即可。 */
    ARCHIVE("archive", "归档"),

    /** 其余长期背景，留在正文。 */
    BODY("body", "正文"),
    ;

    companion object {
        /** 结项/收尾类词：这类内容写进核心只会持续占用预算。 */
        private val ARCHIVE_HINTS = listOf(
            "结项", "收官", "已完成", "已上线", "已归档", "已废弃", "不再维护", "历史",
        )

        /** 长期身份/偏好类词：这些才值得每轮都看到。 */
        private val CORE_HINTS = listOf(
            "用户偏好", "用户要求", "始终", "一律", "永远", "不要", "禁止", "必须",
            "称呼", "语气", "身份", "习惯",
        )

        /**
         * 给一段待写入内容建议落点。
         *
         * @param content 待写入的 Markdown
         * @param targetSection 目标章节标题（替换已有节时已知归属，直接沿用它的位置）
         */
        fun suggest(content: String, targetSection: String? = null): AgentMemoryPlacement {
            targetSection?.let { section ->
                if (section.trim().trimStart('#').trim() == CORE_HEADING_TITLE) return CORE
            }
            if (content.isBlank()) return BODY
            val hitArchive = ARCHIVE_HINTS.count(content::contains)
            val hitCore = CORE_HINTS.count(content::contains)
            return when {
                hitArchive > 0 && hitArchive >= hitCore -> ARCHIVE
                hitCore > 0 -> CORE
                else -> BODY
            }
        }

        /** 一句话理由，随建议一起返回，避免模型把建议当命令。 */
        fun rationale(placement: AgentMemoryPlacement): String = when (placement) {
            CORE -> "看起来是长期稳定的偏好或约束，放进核心区可每轮生效；核心区预算有限，请保持简短"
            ARCHIVE -> "看起来是已收尾的内容，建议留在正文或标注归档日期，避免长期占用注入预算"
            BODY -> "按需检索即可，留在正文"
        }

        private const val CORE_HEADING_TITLE = "核心记忆"
    }
}
