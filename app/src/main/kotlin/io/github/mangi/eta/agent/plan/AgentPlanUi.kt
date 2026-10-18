package io.github.mangi.eta.agent.plan

/**
 * 界面上展示的任务清单摘要。
 *
 * 数据来源是**历史里最后一次 `todo_write` 的回执文本**，而不是直接查库。
 * 理由：UI 层是纯函数式的（`remember(messages)`），让它去碰 suspend 的 DAO 会把
 * 数据流搅乱；而回执本身就带完整清单（见 [AgentPlanTool.buildResultText]），
 * 解析它零副作用，且天然跟随「用户实际看到的历史」。
 *
 * 代价：回执格式一旦改动，解析必须同步 —— 因此 [AgentPlanUi] 有对应单元测试锁定格式。
 */
internal data class AgentPlanUi(
    val done: Int,
    val total: Int,
    /** 当前进行中的步骤标题；没有则为 null。 */
    val running: String?,
    val items: List<Item>,
) {
    data class Item(val label: String, val subject: String)

    val isActive: Boolean get() = total > 0 && done < total

    companion object {
        /** 回执里每条步骤的前缀：`1. [已完成] 收集资料`。 */
        private val ITEM_PATTERN = Regex("""^\s*(\d+)\.\s*\[([^\]]+)]\s*(.+)$""")
        private val PROGRESS_PATTERN = Regex("""(\d+)/(\d+)\s*完成""")
        private val RUNNING_PATTERN = Regex("""当前进行中：(.+?)。""")

        /**
         * 从消息文本提取清单摘要；找不到或格式不符时返回 null（UI 什么都不显示）。
         *
         * 只解析**最后一条**匹配的回执：清单是全量覆盖语义，最后一条即当前全貌。
         */
        fun parseFrom(messageText: String): AgentPlanUi? {
            if (!messageText.contains("任务清单已更新") && !messageText.contains("已清空任务清单")) {
                return null
            }
            if (messageText.contains("已清空任务清单")) {
                return AgentPlanUi(done = 0, total = 0, running = null, items = emptyList())
            }

            val progress = PROGRESS_PATTERN.find(messageText) ?: return null
            val done = progress.groupValues[1].toIntOrNull() ?: return null
            val total = progress.groupValues[2].toIntOrNull() ?: return null
            if (total <= 0) return null

            val items = messageText.lineSequence().mapNotNull { line ->
                val m = ITEM_PATTERN.find(line) ?: return@mapNotNull null
                AgentPlanUi.Item(label = m.groupValues[2].trim(), subject = m.groupValues[3].trim())
            }.toList()

            val running = RUNNING_PATTERN.find(messageText)?.groupValues?.get(1)?.trim()

            return AgentPlanUi(done = done, total = total, running = running, items = items)
        }
    }
}
