package io.github.mangi.eta.agent.plan

/**
 * 计划模式的会话阶段。
 *
 * 与 [AgentPlanStatus]（单条步骤状态）是两套正交的状态：
 * - [AgentPlanStatus] 回答"这一步做到哪了"；
 * - 本枚举回答"整个会话现在处于什么交互姿态"。
 *
 * 状态机（参考 Codex Plan / Kimi EnterPlanMode-ExitPlanMode / DSH plan-mode）：
 * ```
 * NORMAL ──/plan 或 enter_plan_mode──▶ PLANNING
 * PLANNING ──exit_plan_mode──▶ REVIEW
 * REVIEW ──用户批准──▶ EXECUTING ──完成/取消──▶ NORMAL
 * REVIEW ──修改意见──▶ PLANNING
 * REVIEW ──取消──▶ NORMAL
 * ```
 * [PLANNING] 与 [REVIEW] 是只读阶段：工具层会拒绝修改类工具，不能只靠提示词约束。
 */
internal enum class AgentPlanPhase(val wire: String, val label: String) {
    NORMAL("normal", "普通对话"),
    PLANNING("planning", "计划中"),
    REVIEW("review", "待批准"),
    EXECUTING("executing", "执行中"),
    ;

    /** 只读阶段：计划调研与等待批准期间不允许修改任何东西。 */
    val isReadOnly: Boolean get() = this == PLANNING || this == REVIEW

    /** 是否已经存在一个用户可见的计划（待批准或执行中）。 */
    val hasPlan: Boolean get() = this == REVIEW || this == EXECUTING

    companion object {
        /** 宽容解析；未知/空值回退 [NORMAL]（损坏记录不应让整轮对话不可用）。 */
        fun fromWire(value: String?): AgentPlanPhase {
            val normalized = value?.trim()?.lowercase() ?: return NORMAL
            return entries.firstOrNull { it.wire == normalized } ?: NORMAL
        }
    }
}

/** 本轮请求应该注入什么。 */
internal enum class AgentPlanInjectionScope {
    /** 什么都不注入。 */
    NONE,

    /** 只注入清单：普通对话里用户没走审批的任务清单。 */
    CHECKLIST,

    /** 注入计划正文 + 清单：待批准或执行中。 */
    PLAN_AND_CHECKLIST,
}

/**
 * 决定本轮注入范围。
 *
 * 三个阶段语义不同，不能用一个"非 PLANNING"概括 —— 这正是上一版泄漏的来源：
 * 计划结束后 `markdown` 与清单仍保留供回看，若按"非 PLANNING 就注入"判断，
 * 之后每轮闲聊都会白背几千字旧计划。
 *
 * @param hasOpenWork 清单里是否还有 pending / in_progress 项。
 */
internal fun AgentPlanPhase.injectionScope(hasOpenWork: Boolean): AgentPlanInjectionScope = when {
    hasPlan -> AgentPlanInjectionScope.PLAN_AND_CHECKLIST
    this == AgentPlanPhase.NORMAL && hasOpenWork -> AgentPlanInjectionScope.CHECKLIST
    else -> AgentPlanInjectionScope.NONE
}
