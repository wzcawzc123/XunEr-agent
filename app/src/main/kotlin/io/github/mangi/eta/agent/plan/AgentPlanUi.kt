package io.github.mangi.eta.agent.plan

/**
 * 界面上展示的任务清单摘要。
 *
 * 数据来源是**历史里最后一次成功 `todo_write` 的回执文本**，而不是直接查库。
 * 理由：UI 层是纯函数式的（`remember(messages)`），让它去碰 suspend 的 DAO 会把
 * 数据流搅乱；而回执本身就带完整清单（见 [AgentPlanTool.buildResultText]），
 * 解析它零副作用，且天然跟随「用户实际看到的历史」。
 *
 * 代价：回执格式一旦改动，解析必须同步 —— 因此 [AgentPlanUi] 有对应单元测试锁定格式。
 * 失败回执（"失败：…"）不会被解析成摘要，调用方应继续向前找最后一条成功回执。
 */
internal data class AgentPlanUi(
    val done: Int,
    val total: Int,
    /** 当前进行中的步骤标题；没有则为 null。 */
    val running: String?,
    val items: List<Item>,
    /** 已取消条数：不计入完成，但必须让用户看见（否则可用取消刷到 100%）。 */
    val cancelled: Int = 0,
) {
    data class Item(
        val label: String,
        val subject: String,
        /** 解析后的状态；UI 用它选图标/颜色，不再依赖中文 label 做字符串匹配。 */
        val status: AgentPlanStatus = AgentPlanStatus.PENDING,
    )

    /**
     * 是否还需要展示进度条。
     *
     * 有未完成项时展示；全部完成但有取消项时**也要展示** —— 否则界面会以
     * "done == total" 的假象瞬间消失，用户看不到有步骤被放弃。
     */
    val isActive: Boolean get() = total > 0 && (done < total || cancelled > 0)

    /** 全部条目都已结束，但其中包含取消项。 */
    val isTerminalWithCancelled: Boolean get() = total > 0 && done >= total && cancelled > 0

    companion object {
        /** 回执里每条步骤的前缀：`1. [已完成] 收集资料`。 */
        private val ITEM_PATTERN = Regex("""^\s*(\d+)\.\s*\[([^\]]+)]\s*(.+)$""")
        private val PROGRESS_PATTERN = Regex("""(\d+)/(\d+)\s*完成""")
        private val RUNNING_PATTERN = Regex("""当前进行中：(.+?)。""")

        /**
         * 从消息文本提取清单摘要；找不到或格式不符时返回 null（UI 什么都不显示）。
         *
         * 只解析**最后一条**匹配的回执：清单是全量覆盖语义，最后一条即当前全貌。
         * 失败回执返回 null，由调用方继续向前找 —— 一次参数错误不该让旧清单消失。
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

            val items = messageText.lineSequence().mapNotNull { line ->
                val m = ITEM_PATTERN.find(line) ?: return@mapNotNull null
                val label = m.groupValues[2].trim()
                AgentPlanUi.Item(
                    label = label,
                    subject = m.groupValues[3].trim(),
                    status = AgentPlanStatus.fromLabel(label),
                )
            }.toList()

            val running = RUNNING_PATTERN.find(messageText)?.groupValues?.get(1)?.trim()
            val cancelled = items.count { it.status == AgentPlanStatus.CANCELLED }

            // 0/0 且没有取消项：不是有效清单（避免把异常回执当成"已清空"）。
            // 0/0 但有取消项：全部被取消，仍要返回一份摘要 —— 否则 latestPlanUi 会继续
            // 向前找到旧清单，把已经放弃的计划重新显示出来。
            if (total <= 0 && cancelled == 0) return null

            return AgentPlanUi(
                done = done,
                total = total,
                running = running,
                items = items,
                cancelled = cancelled,
            )
        }
    }
}
