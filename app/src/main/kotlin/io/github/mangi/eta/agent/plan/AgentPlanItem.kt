package io.github.mangi.eta.agent.plan

/**
 * 任务清单中的一条步骤。
 *
 * 关于 [subject] 与 [activeForm] 的双形态：列表视图显示名词形态（"修复压缩判据"），
 * 正在执行时显示进行时（"正在修复压缩判据"）。两者都由模型提供，UI 不做字符串拼接 ——
 * 因为中文的进行时不是机械加前缀，让模型直接给更自然；缺省时按 [AgentPlanItem.Companion.fallbackActiveForm]
 * 兜底，保证 UI 永远有字可显示。
 *
 * 这个类**不依赖任何 Android / JSON / 数据库 API**：所有解析与校验都在这里完成，
 * 因此可以用最快的 JVM 单元测试覆盖全部分支。
 */
internal data class AgentPlanItem(
    val id: String,
    val subject: String,
    val activeForm: String,
    val status: AgentPlanStatus,
) {
    companion object {
        /** 进度统计：完成数 / 总数（取消的不计入分母）。 */
        fun progressOf(items: List<AgentPlanItem>): Pair<Int, Int> {
            val counted = items.filterNot { it.status == AgentPlanStatus.CANCELLED }
            return counted.count { it.status == AgentPlanStatus.COMPLETED } to counted.size
        }

        /**
         * 已取消条数。
         *
         * 单独计数而不是并进 [progressOf]：取消**不能**算完成，但也不能当作不存在。
         * 回执、摘要概览与 UI 终态都要明确告诉用户"有几步被放弃了"，
         * 否则模型可以通过取消难做的步骤把进度刷到 100%。
         */
        fun cancelledCount(items: List<AgentPlanItem>): Int =
            items.count { it.status == AgentPlanStatus.CANCELLED }

        /** 是否还有未结束的工作（待开始或进行中）。 */
        fun hasOpenWork(items: List<AgentPlanItem>): Boolean =
            items.any { it.status == AgentPlanStatus.PENDING || it.status == AgentPlanStatus.IN_PROGRESS }
        /** 单条标题上限：够写清楚一件事，又不至于把清单变成文档。 */
        const val MAX_TEXT_CHARS = 120

        /** 清单条数上限：超过后模型通常已经失去对整体结构的把握，不如拆分任务。 */
        const val MAX_ITEMS = 50

        /** 低于这个步数不值得建清单 —— 两三步的事直接做更快。 */
        const val MIN_ITEMS_FOR_PLAN = 3

        /** 模型没给 [activeForm] 时的兜底。 */
        fun fallbackActiveForm(subject: String): String = "正在$subject"

        /**
         * 构造一条步骤。
         *
         * @param index 用于在缺省 id 时生成稳定标识；同一批内唯一即可。
         * @return 成功返回条目，失败返回面向模型的错误说明（中文、可直接读）。
         */
        fun create(
            index: Int,
            id: String?,
            subject: String?,
            activeForm: String?,
            status: AgentPlanStatus?,
        ): Result<AgentPlanItem> {
            val cleanSubject = subject?.trim().orEmpty()
            if (cleanSubject.isEmpty()) {
                return Result.failure(PlanValidationException("第 ${index + 1} 条缺少 subject（步骤标题）"))
            }
            if (cleanSubject.length > MAX_TEXT_CHARS) {
                return Result.failure(
                    PlanValidationException("第 ${index + 1} 条的 subject 超过 $MAX_TEXT_CHARS 字，请精简"),
                )
            }

            val cleanId = id?.trim().orEmpty().ifEmpty { "step-${index + 1}" }
            if (cleanId.length > MAX_TEXT_CHARS) {
                return Result.failure(PlanValidationException("第 ${index + 1} 条的 id 过长"))
            }

            val cleanActive = activeForm?.trim().orEmpty().ifEmpty { fallbackActiveForm(cleanSubject) }
            if (cleanActive.length > MAX_TEXT_CHARS) {
                return Result.failure(PlanValidationException("第 ${index + 1} 条的 activeForm 超过 $MAX_TEXT_CHARS 字"))
            }

            return Result.success(
                AgentPlanItem(
                    id = cleanId,
                    subject = cleanSubject,
                    activeForm = cleanActive,
                    status = status ?: AgentPlanStatus.PENDING,
                ),
            )
        }

        /**
         * 校验整份清单。
         *
         * 额外规则（单条校验覆盖不到的）：
         * - id 必须唯一（否则 UI 与更新逻辑会串行）
         * - 条数不超过 [MAX_ITEMS]
         * - 至多一条进行中（多条会让进度条无法确定"当前在做什么"）
         */
        fun validateAll(items: List<AgentPlanItem>): Result<List<AgentPlanItem>> {
            if (items.isEmpty()) return Result.success(emptyList())

            if (items.size > MAX_ITEMS) {
                return Result.failure(PlanValidationException("清单最多 $MAX_ITEMS 条，当前 ${items.size} 条"))
            }

            val duplicated = items.groupBy { it.id }.filterValues { it.size > 1 }.keys
            if (duplicated.isNotEmpty()) {
                return Result.failure(PlanValidationException("id 重复：${duplicated.joinToString("、")}"))
            }

            val running = items.count { it.status == AgentPlanStatus.IN_PROGRESS }
            if (running > 1) {
                return Result.failure(
                    PlanValidationException("同时有 $running 条处于 in_progress，请只保留一条"),
                )
            }

            return Result.success(items)
        }

        /**
         * 相对上一版的状态转移校验。
         *
         * 首次写入（previous 为空）完全放行：模型可以一次性把"已经做完的步骤"带进来。
         * 后续更新只拦两类最容易造成"进度账本失真"的转移：
         * - 已完成/已取消 → 未完成：必须显式 `reopen=true`，否则视为静默复活；
         * - 已完成 → 已取消：同样需要 `reopen=true`，避免用取消把完成项从分母里抹掉。
         *
         * `pending → completed` 不拦（模型常在一次调用里补记多个已完成步骤），
         * 但回执会给出"跳过 in_progress"的提示，由模型自行纠正。
         */
        fun validateTransitions(
            previous: List<AgentPlanItem>,
            current: List<AgentPlanItem>,
            reopenIds: Set<String> = emptySet(),
        ): Result<Unit> {
            if (previous.isEmpty()) return Result.success(Unit)
            val before = previous.associateBy { it.id }
            current.forEachIndexed { index, item ->
                val old = before[item.id] ?: return@forEachIndexed
                val reopened = item.id in reopenIds
                val regressed = when (old.status) {
                    AgentPlanStatus.COMPLETED ->
                        item.status != AgentPlanStatus.COMPLETED
                    AgentPlanStatus.CANCELLED ->
                        item.status == AgentPlanStatus.PENDING || item.status == AgentPlanStatus.IN_PROGRESS
                    else -> false
                }
                if (regressed && !reopened) {
                    return Result.failure(
                        PlanValidationException(
                            "第 ${index + 1} 条（${item.subject}）从 ${old.status.label} 变为 ${item.status.label}，" +
                                "若要重新打开请在该条传 reopen=true",
                        ),
                    )
                }
            }
            return Result.success(Unit)
        }
    }
}

/** 校验失败：消息直接面向模型，避免再包一层无信息量的错误码。 */
internal class PlanValidationException(message: String) : IllegalArgumentException(message)
