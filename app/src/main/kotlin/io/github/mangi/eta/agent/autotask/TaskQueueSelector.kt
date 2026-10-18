package io.github.mangi.eta.agent.autotask

/**
 * 任务队列的选取逻辑（纯函数）。
 *
 * 为什么从"队列"里挑而不是"遍历全部任务"：
 * 一次唤醒只应该做**一件**事 —— 做多了既拖长电池窗口，也让"结果归因"变难
 * （用户看到通知时不知道是哪个任务触发的）。所以：
 *
 * 1. 只挑**一个**（按创建时间先后，先来先做）；
 * 2. 已经跑过的跳过（第一版：一次任务只跑一次，避免无人看管下反复花钱）；
 * 3. 未启用的跳过。
 *
 * 这套规则与 DeepSeek Harness 的 `agent/inbox` + `queued work wakes driver` 同构：
 * 队列有活 → 唤醒 → 处理一条 → 回到等待。
 */
internal object TaskQueueSelector {

    /**
     * 从 [tasks] 中选出此刻应该执行的**一个**任务。
     *
     * @param state 当前设备状态（充电/WiFi/亮屏）
     * @param nowMillis 当前时刻，用于判定「是否到点」
     * @return 选中的任务；没有可执行的就返回 null（调用方据此决定是否休眠）
     */
    fun pick(tasks: List<AgentTask>, state: DeviceState, nowMillis: Long): AgentTask? = tasks
        .asSequence()
        // 跑过的任务：一次性（after / 无 schedule）不再重复；
        // **重复任务（every / daily）必须放行** —— 否则「每天检查 CI」只会跑一次，
        // 与 ScheduleSpec.repeating 声明的语义矛盾。是否到点由 isDue() 用 lastRunAt 推算。
        .filter { !it.hasRun || it.schedule?.repeating == true }
        // 必须启用且条件满足
        .filter { it.canRun(state, nowMillis) }
        // 先来先做：创建早的先跑，保证顺序可预期
        .minByOrNull { it.createdAt }

    /**
     * 队列概览，供设置页展示。
     *
     * 分成四类而不是简单计数，是因为用户最关心的是"为什么我的任务没跑"：
     * - [waiting]：条件未满足（最常见，也是正常状态）
     * - [ready]：条件已满足，等下一次唤醒
     * - [done]：已执行过
     * - [disabled]：未启用
     */
    data class Overview(
        val waiting: Int,
        val ready: Int,
        val done: Int,
        val disabled: Int,
    ) {
        val total: Int get() = waiting + ready + done + disabled
    }

    fun overview(tasks: List<AgentTask>, state: DeviceState): Overview {
        var waiting = 0
        var ready = 0
        var done = 0
        var disabled = 0
        for (task in tasks) {
            when {
                task.hasRun -> done += 1
                !task.enabled -> disabled += 1
                task.condition.satisfiedBy(state) -> ready += 1
                else -> waiting += 1
            }
        }
        return Overview(waiting = waiting, ready = ready, done = done, disabled = disabled)
    }
}
