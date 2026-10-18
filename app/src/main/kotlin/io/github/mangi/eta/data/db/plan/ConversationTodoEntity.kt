package io.github.mangi.eta.data.db.plan

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert

/**
 * 任务清单的持久化实体。
 *
 * 为什么**不**放进 conversation context checkpoint：
 * 检查点会被压缩重写，而清单的全部价值就是「压缩后仍然记得自己在做什么」。
 * 独立表让它天然免疫压缩，代价只是多一次查询。
 *
 * [position] 单独存一列而不是依赖插入顺序：全量覆盖写入时，UI 与提示都依赖稳定顺序。
 */
@Entity(
    tableName = "conversation_todos",
    primaryKeys = ["conversation_id", "item_id"],
    indices = [Index(value = ["conversation_id", "position"])],
)
internal data class ConversationTodoEntity(
    @ColumnInfo(name = "conversation_id") val conversationId: String,
    @ColumnInfo(name = "item_id") val itemId: String,
    @ColumnInfo(name = "subject") val subject: String,
    @ColumnInfo(name = "active_form") val activeForm: String,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "position") val position: Int,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Dao
internal interface ConversationTodoDao {
    @Query(
        "SELECT * FROM conversation_todos WHERE conversation_id = :conversationId ORDER BY position ASC",
    )
    suspend fun list(conversationId: String): List<ConversationTodoEntity>

    /**
     * 同步读。工具执行本身跑在后台线程（见 AgentLocalTools 的调度约定），
     * 这里提供同步入口是为了避免在工具层引入 `runBlocking` —— 那会把
     * 「当前是否在 IO 线程」这个隐含前提藏进一行代码里，日后挪动调用点就会变成卡顿事故。
     */
    @Query(
        "SELECT * FROM conversation_todos WHERE conversation_id = :conversationId ORDER BY position ASC",
    )
    fun listBlocking(conversationId: String): List<ConversationTodoEntity>

    @Upsert
    suspend fun upsert(items: List<ConversationTodoEntity>)

    @Query("DELETE FROM conversation_todos WHERE conversation_id = :conversationId")
    suspend fun deleteAll(conversationId: String)

    /**
     * 全量覆盖：先删后插，放在同一事务里。
     *
     * 不用「差量更新」是刻意的选择 —— 单线程 agent 下，覆盖式写入永远不会出现
     * 「表里残留上一版步骤」这类脏状态，而这正是差量更新最常见的 bug 来源。
     */
    @Transaction
    suspend fun replaceAll(conversationId: String, items: List<ConversationTodoEntity>) {
        deleteAll(conversationId)
        if (items.isNotEmpty()) upsert(items)
    }

    /** 同步版全量覆盖，供工具层（已在后台线程）使用。 */
    @Transaction
    fun replaceAllBlocking(conversationId: String, items: List<ConversationTodoEntity>) {
        deleteAllBlocking(conversationId)
        if (items.isNotEmpty()) upsertBlocking(items)
    }

    @Query("DELETE FROM conversation_todos WHERE conversation_id = :conversationId")
    fun deleteAllBlocking(conversationId: String)

    @Upsert
    fun upsertBlocking(items: List<ConversationTodoEntity>)
}
