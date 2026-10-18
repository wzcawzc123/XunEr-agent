package io.github.mangi.eta.agent.autotask

/**
 * 一条自动任务。
 *
 * 定位：**用户或模型在对话中创建的一条"待办"**，在条件满足时由 Eta 自己执行一次。
 *
 * 三道护栏写在模型里，而不是只写在执行逻辑里 —— 这样任何入口（工具、UI、脚本）
 * 都必须遵守：
 * 1. [maxRounds]：单次执行最多几轮，防失控；
 * 2. [enabled]：默认关闭，必须显式开启；
 * 3. [readOnly]：第一版只允许只读操作。
 */
internal data class AgentTask(
    val id: String,
    val title: String,
    /** 交给 Agent 的指令正文。 */
    val prompt: String,
    val condition: TaskCondition = TaskCondition.CONSERVATIVE,
    /** 单次执行的轮数上限；超限即停止并在结果里说明。 */
    val maxRounds: Int = DEFAULT_MAX_ROUNDS,
    /** 默认关闭：新建的任务不会自己跑，必须用户确认。 */
    val enabled: Boolean = false,
    /** 第一版恒为 true；开放写操作前必须先扩护栏（额度、确认、白名单）。 */
    val readOnly: Boolean = true,
    /**
     * 任务所属会话。
     *
     * 执行时**在同一个会话追加一轮**，而不是新建会话：
     * 这样自动轮能继承原会话上下文（知道你们之前聊了什么），
     * 会话列表也不会被"自动任务"刷屏。null 表示尚未绑定（旧数据）。
     */
    val conversationId: String? = null,
    /**
     * 时间语义（借鉴 dsh 的 schedule）。到点 + 条件满足才执行。
     * null 表示"只要条件满足就跑"（旧行为，向后兼容）。
     */
    val schedule: ScheduleSpec? = null,
    /**
     * 是否仍活跃。一次性任务投递后转 inactive（但不删除 —— 保留历史，
     * 用户能回看"这条提醒什么时候跑过"）。
     */
    val active: Boolean = true,
    val lastRunAt: Long? = null,
    val lastResult: String? = null,
    val createdAt: Long,
) {
    /** 是否允许此刻执行：启用 + 活跃 + 条件满足 + 到点。 */
    fun canRun(state: DeviceState, nowMillis: Long): Boolean =
        enabled && active && condition.satisfiedBy(state) && isDue(nowMillis)

    /**
     * 是否已经彻底做完（一次性任务执行过即为真）。
     *
     * **必须在所有地方用同一份判断**：这次审查发现同一个 null 语义
     * （`schedule == null` 表示一次性）在 execute / list / export 三处各写了一遍，
     * 三处都写错成 `!= false` / `== false`，结果一次性任务永远「没做完」：
     * 列表显示「等待中」、导出继续带给守护脚本、脚本每分钟重复唤醒。
     */
    val isFinished: Boolean
        get() = schedule?.repeating != true && lastRunAt != null

    /** 是否已到点（无 schedule 视为"永远到点"，保持旧行为）。 */
    fun isDue(nowMillis: Long): Boolean {
        val spec = schedule ?: return true
        return nowMillis >= spec.nextRunAtMillis(nowMillis, lastRunAt)
    }

    /** 是否已经跑过（第一版：一次任务只跑一次，避免无人看管下反复花钱）。 */
    val hasRun: Boolean get() = lastRunAt != null

    companion object {
        /** 单次任务最多 5 轮：足够"查一次 + 抓日志 + 总结"，再多就该人来判断了。 */
        const val DEFAULT_MAX_ROUNDS = 5
        const val MAX_ALLOWED_ROUNDS = 20
        const val MAX_TITLE_CHARS = 60
        const val MAX_PROMPT_CHARS = 2_000

        /**
         * 构造并校验。
         *
         * @return 成功返回任务，失败返回面向用户的中文原因。
         */
        fun create(
            id: String,
            title: String,
            prompt: String,
            condition: TaskCondition = TaskCondition.CONSERVATIVE,
            maxRounds: Int = DEFAULT_MAX_ROUNDS,
            enabled: Boolean = false,
            conversationId: String? = null,
            nowMillis: Long,
        ): Result<AgentTask> {
            val cleanTitle = title.trim()
            val cleanPrompt = prompt.trim()
            if (cleanTitle.isEmpty()) return Result.failure(TaskValidationException("任务标题不能为空"))
            if (cleanTitle.length > MAX_TITLE_CHARS) {
                return Result.failure(TaskValidationException("标题超过 $MAX_TITLE_CHARS 字"))
            }
            if (cleanPrompt.isEmpty()) return Result.failure(TaskValidationException("任务指令不能为空"))
            if (cleanPrompt.length > MAX_PROMPT_CHARS) {
                return Result.failure(TaskValidationException("指令超过 $MAX_PROMPT_CHARS 字，请拆分"))
            }
            if (maxRounds !in 1..MAX_ALLOWED_ROUNDS) {
                return Result.failure(
                    TaskValidationException("轮数上限必须在 1..$MAX_ALLOWED_ROUNDS，实际 $maxRounds"),
                )
            }
            return Result.success(
                AgentTask(
                    id = id,
                    title = cleanTitle,
                    prompt = cleanPrompt,
                    condition = condition,
                    maxRounds = maxRounds,
                    enabled = enabled,
                    conversationId = conversationId,
                    createdAt = nowMillis,
                ),
            )
        }
    }
}

internal class TaskValidationException(message: String) : IllegalArgumentException(message)
