package io.github.mangi.eta.agent.plan

/**
 * 任务清单中单条步骤的状态。
 *
 * 只保留四个值：比起把「删除」也做成状态（Claude Code 的做法），这里直接允许覆盖式写入
 * 时省略该条 —— 单线程 agent 没有并发删除的需求，少一个状态就少一类非法转换。
 *
 * [wire] 是与模型交互时使用的小写下划线字面量，不要改：提示词与工具 schema 里都写死了这个名字。
 */
internal enum class AgentPlanStatus(val wire: String, val label: String) {
    PENDING("pending", "待开始"),
    IN_PROGRESS("in_progress", "进行中"),
    COMPLETED("completed", "已完成"),
    CANCELLED("cancelled", "已取消"),
    ;

    /** 终态不再需要模型关注；进度统计与「当前进行项」的推导都靠它。 */
    val isTerminal: Boolean get() = this == COMPLETED || this == CANCELLED

    companion object {
        /**
         * 宽容解析：接受大小写差异与首尾空白，但**不做别名映射**（例如不接受 "done"）。
         * 理由是别名会让模型的错误写法静默通过，掩盖提示词的缺陷。
         */
        fun fromWire(value: String?): AgentPlanStatus? {
            val normalized = value?.trim()?.lowercase() ?: return null
            return entries.firstOrNull { it.wire == normalized }
        }

        /**
         * 从界面回执里的中文标签反解状态。
         *
         * 只用于 [io.github.mangi.eta.agent.plan.AgentPlanUi] 解析历史回执 —— 新代码请优先用
         * [wire] 或枚举本身。未知标签回退 [PENDING]，与旧版解析行为一致（宁可显示"待开始"，
         * 也不让整张清单读不出来）。
         */
        fun fromLabel(value: String?): AgentPlanStatus {
            val normalized = value?.trim() ?: return PENDING
            return entries.firstOrNull { it.label == normalized } ?: PENDING
        }
    }
}
