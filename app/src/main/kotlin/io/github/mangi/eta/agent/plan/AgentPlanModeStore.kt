package io.github.mangi.eta.agent.plan

import io.github.mangi.eta.data.db.plan.ConversationPlanDao
import io.github.mangi.eta.data.db.plan.ConversationPlanEntity

/**
 * 计划模式状态的领域层读写入口。
 *
 * 这一层是 [AgentPlanModeState]（纯模型）与 [ConversationPlanEntity]（Room 实体）之间
 * 唯一的转换点；工具层、运行时与提示词层都只依赖本类，不直接碰 DAO。
 *
 * 提供 suspend 与 blocking 两套入口，理由与 [AgentPlanStore] 相同：UI/备份在协程里用前者，
 * 工具与运行时在专用后台线程用后者，避免在调用点偷偷 `runBlocking`。
 */
internal data class AgentPlanModeState(
    val planId: String,
    val phase: AgentPlanPhase,
    val version: Int,
    val approvedVersion: Int?,
    val markdown: String,
    val createdAt: Long,
    val updatedAt: Long,
) {
    /** 执行阶段是否绑定在"当前这一版"的批准上。 */
    val isExecutingApprovedVersion: Boolean
        get() = phase == AgentPlanPhase.EXECUTING && approvedVersion != null && approvedVersion == version
}

internal class AgentPlanModeStore(private val dao: ConversationPlanDao) {

    suspend fun get(conversationId: String): AgentPlanModeState? {
        if (conversationId.isBlank()) return null
        return dao.get(conversationId)?.toState()
    }

    fun getBlocking(conversationId: String): AgentPlanModeState? {
        if (conversationId.isBlank()) return null
        return dao.getBlocking(conversationId)?.toState()
    }

    fun phaseBlocking(conversationId: String): AgentPlanPhase =
        getBlocking(conversationId)?.phase ?: AgentPlanPhase.NORMAL

    /** 进入计划模式：开启一份新计划（版本归零），旧计划正文保留到下次提交再覆盖。 */
    suspend fun enterPlanning(conversationId: String, planId: String, nowMillis: Long): AgentPlanModeState {
        require(conversationId.isNotBlank()) { "conversationId 不能为空" }
        val previous = dao.get(conversationId)
        val state = AgentPlanModeState(
            planId = planId,
            phase = AgentPlanPhase.PLANNING,
            version = 0,
            approvedVersion = null,
            markdown = "",
            createdAt = nowMillis,
            updatedAt = nowMillis,
        )
        dao.upsert(state.toEntity(conversationId))
        return state
    }

    /**
     * 提交计划供审批：版本 +1，阶段置 REVIEW。
     *
     * `approved_version` 清零 —— 新版本尚未被批准，执行层不能用旧批准蒙混过关。
     */
    suspend fun submit(
        conversationId: String,
        markdown: String,
        nowMillis: Long,
    ): AgentPlanModeState {
        require(conversationId.isNotBlank()) { "conversationId 不能为空" }
        val previous = dao.get(conversationId)
        val version = (previous?.version ?: 0) + 1
        val state = AgentPlanModeState(
            planId = previous?.planId?.takeIf { it.isNotBlank() } ?: "plan-$nowMillis",
            phase = AgentPlanPhase.REVIEW,
            version = version,
            approvedVersion = null,
            markdown = markdown,
            createdAt = previous?.createdAt ?: nowMillis,
            updatedAt = nowMillis,
        )
        dao.upsert(state.toEntity(conversationId))
        return state
    }

    /**
     * 用户批准：绑定 `approved_version = version` 并进入 EXECUTING。
     *
     * @return 批准后的状态；没有待批准计划时返回 null。
     */
    suspend fun approve(conversationId: String, nowMillis: Long): AgentPlanModeState? {
        val previous = dao.get(conversationId) ?: return null
        if (AgentPlanPhase.fromWire(previous.phase) != AgentPlanPhase.REVIEW || previous.version <= 0) return null
        val state = previous.toState().copy(
            phase = AgentPlanPhase.EXECUTING,
            approvedVersion = previous.version,
            updatedAt = nowMillis,
        )
        dao.upsert(state.toEntity(conversationId))
        return state
    }

    /** 用户提出修改意见：回到 PLANNING，等待模型提交下一版。 */
    suspend fun reopenPlanning(conversationId: String, nowMillis: Long): AgentPlanModeState? {
        val previous = dao.get(conversationId) ?: return null
        val state = previous.toState().copy(
            phase = AgentPlanPhase.PLANNING,
            approvedVersion = null,
            updatedAt = nowMillis,
        )
        dao.upsert(state.toEntity(conversationId))
        return state
    }

    /** 用户取消计划：回到普通对话；保留正文与版本号，便于回看但不再可执行。 */
    suspend fun cancel(conversationId: String, nowMillis: Long): AgentPlanModeState? {
        val previous = dao.get(conversationId) ?: return null
        val state = previous.toState().copy(
            phase = AgentPlanPhase.NORMAL,
            approvedVersion = null,
            updatedAt = nowMillis,
        )
        dao.upsert(state.toEntity(conversationId))
        return state
    }

    /** 执行完成/放弃：回到普通对话。 */
    suspend fun finish(conversationId: String, nowMillis: Long): AgentPlanModeState? =
        cancel(conversationId, nowMillis)

    // ---------- 工具/运行时使用的同步入口（调用方必须在后台线程） ----------

    fun enterPlanningBlocking(conversationId: String, planId: String, nowMillis: Long): AgentPlanModeState {
        require(conversationId.isNotBlank()) { "conversationId 不能为空" }
        val previous = dao.getBlocking(conversationId)
        val state = AgentPlanModeState(
            planId = planId,
            phase = AgentPlanPhase.PLANNING,
            version = 0,
            approvedVersion = null,
            markdown = "",
            createdAt = nowMillis,
            updatedAt = nowMillis,
        )
        dao.upsertBlocking(state.toEntity(conversationId))
        return state
    }

    fun submitBlocking(conversationId: String, markdown: String, nowMillis: Long): AgentPlanModeState {
        require(conversationId.isNotBlank()) { "conversationId 不能为空" }
        val previous = dao.getBlocking(conversationId)
        val version = (previous?.version ?: 0) + 1
        val state = AgentPlanModeState(
            planId = previous?.planId?.takeIf { it.isNotBlank() } ?: "plan-$nowMillis",
            phase = AgentPlanPhase.REVIEW,
            version = version,
            approvedVersion = null,
            markdown = markdown,
            createdAt = previous?.createdAt ?: nowMillis,
            updatedAt = nowMillis,
        )
        dao.upsertBlocking(state.toEntity(conversationId))
        return state
    }

    fun approveBlocking(conversationId: String, nowMillis: Long): AgentPlanModeState? {
        val previous = dao.getBlocking(conversationId) ?: return null
        if (AgentPlanPhase.fromWire(previous.phase) != AgentPlanPhase.REVIEW || previous.version <= 0) return null
        val state = previous.toState().copy(
            phase = AgentPlanPhase.EXECUTING,
            approvedVersion = previous.version,
            updatedAt = nowMillis,
        )
        dao.upsertBlocking(state.toEntity(conversationId))
        return state
    }

    /**
     * 执行期改动了已批准的范围：拿当前正文重新走一次待批准。
     *
     * 与 [reopenPlanningBlocking] 的区别：这里**不回到规划**，直接进 REVIEW。
     * 用户批准的是一个具体版本；步骤结构变了就必须重新确认那一版，
     * 否则"先改清单再继续"就是绕过批准的后门。
     *
     * 版本 +1、`approved_version` 归零，旧批准自动失效。
     */
    fun reReviewBlocking(conversationId: String, nowMillis: Long): AgentPlanModeState? {
        val previous = dao.getBlocking(conversationId) ?: return null
        val markdown = previous.markdown.takeIf { it.isNotBlank() } ?: return null
        return submitBlocking(conversationId, markdown, nowMillis)
    }

    fun reopenPlanningBlocking(conversationId: String, nowMillis: Long): AgentPlanModeState? {
        val previous = dao.getBlocking(conversationId) ?: return null
        val state = previous.toState().copy(
            phase = AgentPlanPhase.PLANNING,
            approvedVersion = null,
            updatedAt = nowMillis,
        )
        dao.upsertBlocking(state.toEntity(conversationId))
        return state
    }

    fun cancelBlocking(conversationId: String, nowMillis: Long): AgentPlanModeState? {
        val previous = dao.getBlocking(conversationId) ?: return null
        val state = previous.toState().copy(
            phase = AgentPlanPhase.NORMAL,
            approvedVersion = null,
            updatedAt = nowMillis,
        )
        dao.upsertBlocking(state.toEntity(conversationId))
        return state
    }

    suspend fun clear(conversationId: String) {
        if (conversationId.isNotBlank()) dao.delete(conversationId)
    }

    fun clearBlocking(conversationId: String) {
        if (conversationId.isNotBlank()) dao.deleteBlocking(conversationId)
    }

    suspend fun clearAll() = dao.deleteAll()

    companion object {
        /**
         * 执行阶段是否已经可以收尾：没有待开始/进行中的步骤（空清单也算）。
         *
         * 自动化收尾而不是要求模型再调一个 `finish_plan`：手机端模型很容易忘记收尾工具，
         * 而"所有步骤都结束"本身已经是明确信号。收尾只把阶段切回 NORMAL，计划正文保留。
         */
        fun shouldFinish(phase: AgentPlanPhase, items: List<AgentPlanItem>): Boolean =
            phase == AgentPlanPhase.EXECUTING && !AgentPlanItem.hasOpenWork(items)
    }

    private fun ConversationPlanEntity.toState(): AgentPlanModeState = AgentPlanModeState(
        planId = planId,
        phase = AgentPlanPhase.fromWire(phase),
        version = version,
        approvedVersion = approvedVersion,
        markdown = markdown,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun AgentPlanModeState.toEntity(conversationId: String): ConversationPlanEntity =
        ConversationPlanEntity(
            conversationId = conversationId,
            planId = planId,
            phase = phase.wire,
            version = version,
            approvedVersion = approvedVersion,
            markdown = markdown,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
}
