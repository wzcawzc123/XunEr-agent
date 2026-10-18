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
    val lastRunAt: Long? = null,
    val lastResult: String? = null,
    val createdAt: Long,
) {
    /** 是否允许此刻执行。 */
    fun canRun(state: DeviceState): Boolean = enabled && condition.satisfiedBy(state)

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
                    createdAt = nowMillis,
                ),
            )
        }
    }
}

internal class TaskValidationException(message: String) : IllegalArgumentException(message)
