package io.github.mangi.eta.agent.autotask

/**
 * 自动任务的"点火决策"：给定任务列表与设备状态，决定这一轮该做什么。
 *
 * 单独成类而不塞进触发器，是为了让**决策**与**副作用**分离：
 * - 这里纯函数，可以穷举所有状态组合来测；
 * - 真正的"启动 Agent、发通知"留给调用方（需要 Context / Service）。
 *
 * 一次唤醒只做一件事 —— 与 DeepSeek Harness 的 `queued work wakes driver`
 * 同构：队列有活 → 处理一条 → 回到等待。
 */
internal object AutoTaskScheduler {

    /** 决策结果。 */
    sealed interface Decision {
        /** 没有可执行任务（没任务 / 未启用 / 条件不满足 / 都跑过了）。 */
        data object Idle : Decision

        /** 该执行这一条。 */
        data class Run(val task: AgentTask) : Decision
    }

    /**
     * 决策。
     *
     * @param tasks 全部任务（不需要预筛 —— 筛选规则集中在这里，避免多处口径不一）
     * @param state 当前设备状态
     * @param nowMillis 当前时间，用于记录执行时刻（但不在本函数落地）
     */
    fun decide(tasks: List<AgentTask>, state: DeviceState, nowMillis: Long): Decision {
        // 一次只做一件：pick 已按 createdAt 排序并跳过不可执行项
        val task = TaskQueueSelector.pick(tasks, state, nowMillis) ?: return Decision.Idle
        return Decision.Run(task)
    }

    /**
     * 生成执行前给用户看的说明（用于通知标题/会话里的一句话）。
     *
     * 之所以要一句话说明「为什么现在跑」：用户可能已经忘了自己建过这条任务，
     * 突然收到结果会困惑。
     */
    fun describeRun(task: AgentTask): String =
        "自动任务「${task.title}」已执行（条件：${task.condition.describe()}）"

    /**
     * 生成"未能执行"的解释，供设置页展示「为什么我的任务没跑」。
     *
     * 这是最容易被忽略、但对用户最重要的一块 —— 任务不跑时的沉默最让人困惑。
     */
    fun explainIdle(tasks: List<AgentTask>, state: DeviceState, nowMillis: Long = Long.MAX_VALUE): String = when {
        tasks.isEmpty() -> "还没有自动任务"
        tasks.none { it.enabled } -> "有 ${tasks.size} 个任务，但都未启用"
        tasks.all { it.hasRun } -> "所有任务都已执行过（第一版每个任务只跑一次）"
        else -> {
            val pending = tasks.filter { it.enabled && !it.hasRun }
            val unmet = pending.mapNotNull { task ->
                if (task.condition.satisfiedBy(state) && task.isDue(nowMillis)) null else task.condition.describe()
            }.distinct()
            if (unmet.isEmpty()) {
                "条件已满足，等下一次唤醒"
            } else {
                "等待条件：${unmet.joinToString("；")}"
            }
        }
    }
}
