package io.github.mangi.eta.agent.plan

/**
 * 计划模式 UI 入口的状态映射。
 *
 * 单独抽出来是为了让「点一下按钮会发生什么」成为可测试的纯函数：
 * 真实入口在移动端是输入栏 pill，而不是 `/plan` 斜杠命令，
 * 因此这里的映射一旦写错（例如 REVIEW 阶段被误判成"退出"），
 * 用户会在毫无提示的情况下丢掉一份待审批的计划。
 */
internal object AgentPlanModeEntry {

    enum class ToggleAction {
        /** 进入只读规划。 */
        ENTER_PLANNING,

        /** 取消当前计划并回到普通对话。 */
        EXIT_PLANNING,

        /** 什么都不做，由审批条接管。 */
        NONE,
    }

    fun toggleAction(phase: AgentPlanPhase): ToggleAction = when (phase) {
        AgentPlanPhase.NORMAL -> ToggleAction.ENTER_PLANNING
        AgentPlanPhase.PLANNING -> ToggleAction.EXIT_PLANNING
        AgentPlanPhase.EXECUTING -> ToggleAction.EXIT_PLANNING
        // 待审批时按钮不承担"退出"语义：这里的取消是放弃一份已经写好的方案，
        // 必须由审批条上明确的「取消计划」承担，不能被一次误触执行。
        AgentPlanPhase.REVIEW -> ToggleAction.NONE
    }

    /** 是否显示审批条。 */
    fun showsApproval(phase: AgentPlanPhase): Boolean = phase == AgentPlanPhase.REVIEW

    /** 输入栏 pill 是否显示（审批阶段由审批条替代）。 */
    fun showsToggle(phase: AgentPlanPhase): Boolean = !showsApproval(phase)
}
