package io.github.mangi.eta.data.db.plan

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert

/**
 * 会话级计划模式状态与计划文档。
 *
 * 与 [ConversationTodoEntity] 的分工：
 * - 本表只存**计划阶段**（planning / review / executing）、版本、批准的版本和 Markdown 计划正文；
 * - 步骤仍存在 `conversation_todos`，审批通过后由执行阶段继续维护。
 *
 * 一个会话只保留一条**当前**计划记录（conversation_id 做主键）：重新规划时覆盖，
 * 但 `version` 递增、`approved_version` 保留，足以判断"执行的是不是用户批准的那一版"。
 * 历史版本不做表内保留（后续如需审计，再加 `plan_events`）。
 */
@Entity(tableName = "conversation_plans")
internal data class ConversationPlanEntity(
    @PrimaryKey
    @ColumnInfo(name = "conversation_id") val conversationId: String,
    @ColumnInfo(name = "plan_id") val planId: String,
    @ColumnInfo(name = "phase") val phase: String,
    @ColumnInfo(name = "version") val version: Int,
    /** 用户批准时对应的版本；未批准为 null。执行必须绑定这一版。 */
    @ColumnInfo(name = "approved_version") val approvedVersion: Int?,
    @ColumnInfo(name = "markdown") val markdown: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Dao
internal interface ConversationPlanDao {
    @Query("SELECT * FROM conversation_plans WHERE conversation_id = :conversationId")
    suspend fun get(conversationId: String): ConversationPlanEntity?

    /** 工具与运行时在后台线程同步读取，理由同 ConversationTodoDao.listBlocking。 */
    @Query("SELECT * FROM conversation_plans WHERE conversation_id = :conversationId")
    fun getBlocking(conversationId: String): ConversationPlanEntity?

    @Upsert
    suspend fun upsert(plan: ConversationPlanEntity)

    @Upsert
    fun upsertBlocking(plan: ConversationPlanEntity)

    @Query("DELETE FROM conversation_plans WHERE conversation_id = :conversationId")
    suspend fun delete(conversationId: String)

    @Query("DELETE FROM conversation_plans WHERE conversation_id = :conversationId")
    fun deleteBlocking(conversationId: String)

    @Query("DELETE FROM conversation_plans")
    suspend fun deleteAll()
}
