package io.github.mangi.eta.agent.plan

/**
 * 计划模式的文本入口与审批路由。
 *
 * 支持的协议（与 `/plan` 命令、CLI 类 agent 的审批习惯对齐）：
 * - `/plan <任务>`：进入计划模式并开始调研；`/plan off`：退出/取消计划；
 * - REVIEW 阶段回复「批准」：绑定当前版本并进入执行；
 * - REVIEW 阶段回复「取消」：放弃计划、清空清单；
 * - REVIEW 阶段回复其他内容：视为修改意见，回到 PLANNING 继续完善。
 *
 * 之所以放在运行时而不是 UI：外部入口（语音、自动任务转发、分享）没有输入框，
 * 但都汇聚到同一条 run 请求；在这里做一次路由，所有入口自动获得同样的计划语义。
 */
internal object AgentPlanModeRouter {

    data class RoutedPrompt(
        val prompt: String,
        val phase: AgentPlanPhase,
    )

    /**
     * 批准/取消一律**整句精确匹配**，不做前缀匹配。
     *
     * 前缀匹配曾把「执行前先确认备份」判成批准（`startsWith("执行")` + 长度 ≤12），
     * 直接把会话推进 EXECUTING 并开始改设备；「取消按钮的颜色改成红色」同理会被判成
     * 放弃计划。用户的修改意见被当成命令执行，是这个模块最危险的一类误判。
     *
     * 代价：「批准，顺便加一步测试」这类"命令 + 意见"会退化为修改意见（重新规划）。
     * 这个方向的失败是安全的——多问一次，而不是多改一次。
     */
    private val APPROVAL_EXACT = setOf(
        "批准", "批准并执行", "同意", "通过", "确认", "可以", "好", "好的", "行", "开始", "开始执行",
        "执行", "干吧", "开工", "approve", "approved", "ok", "okay", "yes", "go", "start", "y",
    )
    private val CANCEL_EXACT = setOf(
        "取消", "取消计划", "放弃", "放弃计划", "不做了", "不用了", "算了", "停止",
        "cancel", "reject", "no", "stop",
    )

    fun route(
        rawPrompt: String,
        conversationId: String?,
        modeStore: AgentPlanModeStore,
        planStore: AgentPlanStore,
        nowMillis: Long,
    ): RoutedPrompt {
        if (conversationId.isNullOrBlank()) return RoutedPrompt(rawPrompt, AgentPlanPhase.NORMAL)
        val trimmed = rawPrompt.trim()
        val current = modeStore.phaseBlocking(conversationId)

        val lower = trimmed.lowercase()
        // 1) /plan 命令优先于当前阶段：它是显式的用户意图。
        // 只认 "/plan" 或 "/plan xxx"，避免把 "/planner" 之类误判成命令。
        if (lower == "/plan" || lower.startsWith("/plan ")) {
            // "/plan" 长度为 5；startsWith 忽略大小写，removePrefix 不忽略，用 substring 更稳。
            val rest = trimmed.substring(5).trim()
            if (rest.equals("off", ignoreCase = true)) {
                modeStore.cancelBlocking(conversationId, nowMillis)
                planStore.replaceBlocking(conversationId, emptyList(), nowMillis)
                return RoutedPrompt("用户已退出计划模式。", AgentPlanPhase.NORMAL)
            }
            // 执行中不允许静默丢弃已批准计划；先让模型确认。
            if (current == AgentPlanPhase.EXECUTING) {
                return RoutedPrompt(
                    "用户请求重新规划，但当前已批准的计划正在执行。" +
                        "请先向用户确认是否放弃当前计划（回复「取消」）；确认前不要修改任何东西。",
                    current,
                )
            }
            modeStore.enterPlanningBlocking(conversationId, "plan-$nowMillis", nowMillis)
            planStore.replaceBlocking(conversationId, emptyList(), nowMillis)
            return RoutedPrompt(
                rest.ifBlank { "请先只读调研，然后制定计划并提交审批。" },
                AgentPlanPhase.PLANNING,
            )
        }

        // 2) 规划中/执行中也允许用户直接叫停；否则"取消"会被当成普通消息，模型只能猜。
        if (current == AgentPlanPhase.PLANNING || current == AgentPlanPhase.EXECUTING) {
            val normalized = normalize(trimmed)
            if (isCancel(normalized)) {
                modeStore.cancelBlocking(conversationId, nowMillis)
                planStore.replaceBlocking(conversationId, emptyList(), nowMillis)
                return RoutedPrompt(
                    if (current == AgentPlanPhase.PLANNING) {
                        "用户已取消计划，回到普通对话。用户消息：$trimmed"
                    } else {
                        "用户已中止执行并取消计划，回到普通对话。用户消息：$trimmed"
                    },
                    AgentPlanPhase.NORMAL,
                )
            }
            if (current == AgentPlanPhase.PLANNING && isApproval(normalized)) {
                return RoutedPrompt(
                    "计划尚未提交，不能批准。请先只读调研并调用 ${AgentPlanModeTool.EXIT_NAME} 提交计划。",
                    AgentPlanPhase.PLANNING,
                )
            }
        }

        // 3) 只在 REVIEW 阶段解释"批准/取消/修改意见"。
        if (current == AgentPlanPhase.REVIEW) {
            val normalized = normalize(trimmed)
            if (isCancel(normalized)) {
                modeStore.cancelBlocking(conversationId, nowMillis)
                planStore.replaceBlocking(conversationId, emptyList(), nowMillis)
                return RoutedPrompt("用户已取消计划，回到普通对话。用户消息：$trimmed", AgentPlanPhase.NORMAL)
            }
            if (isApproval(normalized)) {
                val approved = modeStore.approveBlocking(conversationId, nowMillis)
                if (approved != null) {
                    // 批准后把第一条待开始置为进行中：给模型一个明确的起点，
                    // 也让进度条/尾部注入立刻有"当前步骤"，不必等模型自己想起来。
                    markFirstPendingAsRunning(planStore, conversationId, nowMillis)
                    return RoutedPrompt(
                        "用户已批准计划（v${approved.approvedVersion}）。请按已批准的任务清单开始执行；" +
                            "不要重复已完成步骤；如需改变已批准的范围，先说明并重新提交计划。",
                        AgentPlanPhase.EXECUTING,
                    )
                }
            }
            // 其他内容一律视为修改意见；即使看起来像"同意"但批准失败，也回到规划避免假执行。
            modeStore.reopenPlanningBlocking(conversationId, nowMillis)
            return RoutedPrompt(
                "用户对已提交计划提出修改意见：$trimmed\n" +
                    "请据此更新计划，完成后再次调用 ${AgentPlanModeTool.EXIT_NAME} 提交审批。",
                AgentPlanPhase.PLANNING,
            )
        }

        return RoutedPrompt(rawPrompt, current)
    }

    /** 批准后若没有进行中项，把第一条待开始置为进行中。已有进行中时保持模型的选择。 */
    private fun markFirstPendingAsRunning(
        planStore: AgentPlanStore,
        conversationId: String,
        nowMillis: Long,
    ) {
        val items = planStore.loadBlocking(conversationId)
        if (items.isEmpty()) return
        if (items.any { it.status == AgentPlanStatus.IN_PROGRESS }) return
        val index = items.indexOfFirst { it.status == AgentPlanStatus.PENDING }
        if (index < 0) return
        val updated = items.toMutableList().also { list ->
            list[index] = list[index].copy(status = AgentPlanStatus.IN_PROGRESS)
        }
        planStore.replaceBlocking(conversationId, updated, nowMillis)
    }

    private fun normalize(text: String): String = text
        .trim()
        .lowercase()
        .trimEnd('。', '，', '！', '!', '.', ',', '~', '～')

    private fun isApproval(text: String): Boolean = text in APPROVAL_EXACT

    private fun isCancel(text: String): Boolean = text in CANCEL_EXACT
}
