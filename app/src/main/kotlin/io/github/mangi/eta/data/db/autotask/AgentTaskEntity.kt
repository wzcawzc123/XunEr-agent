package io.github.mangi.eta.data.db.autotask

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert

/**
 * 自动任务的持久化实体。
 *
 * 独立的表，不并进 conversations：任务的生命周期比单个会话长得多
 * （"帮我盯 CI"这件事可能跨十几个会话），把它挂在会话上会跟着会话一起被删除或归档。
 */
@Entity(
    tableName = "agent_tasks",
    indices = [Index(value = ["enabled", "last_run_at"])],
)
internal data class AgentTaskEntity(
    @PrimaryKey val id: String,
    val title: String,
    val prompt: String,
    /** 条件用位掩码存：1=充电 2=WiFi 4=灭屏。比三个布尔列更好扩展。 */
    @ColumnInfo(name = "condition_mask") val conditionMask: Int,
    @ColumnInfo(name = "max_rounds") val maxRounds: Int,
    val enabled: Boolean,
    @ColumnInfo(name = "read_only") val readOnly: Boolean,
    @ColumnInfo(name = "conversation_id") val conversationId: String?,
    @ColumnInfo(name = "last_run_at") val lastRunAt: Long?,
    @ColumnInfo(name = "last_result") val lastResult: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    /**
     * 是否仍活跃。一次性任务（after / 无 schedule）投递一次后转 false，
     * 让列表显示「已执行」而不是永远停在「等待中」；重复任务保持 true。
     *
     * v26 新增：此前只有域模型有 active，Entity 没有 —— 赋值写不进去，
     * 读回来永远是默认 true，等于该字段形同虚设。
     */
    @ColumnInfo(name = "active", defaultValue = "1") val active: Boolean = true,
    /**
     * 时间语义的持久化形式（`after:delay:anchor` / `every:interval` / `daily:h:m`）。
     * 空串表示「无时间限制，条件满足就跑」。
     *
     * v27 新增：此前 Entity 缺这一列，写入时被静默丢弃 → 所有时间语义在真机上失效。
     */
    @ColumnInfo(name = "schedule_spec", defaultValue = "''") val scheduleSpec: String = "",
)

@Dao
internal interface AgentTaskDao {
    @Query("SELECT * FROM agent_tasks ORDER BY created_at ASC")
    suspend fun all(): List<AgentTaskEntity>

    /** 同步版：供运行时（已在后台线程）挑选任务。 */
    @Query("SELECT * FROM agent_tasks ORDER BY created_at ASC")
    fun allBlocking(): List<AgentTaskEntity>

    /** 只取可能执行的：已启用且没跑过。条件判定留给上层（需要设备状态）。 */
    @Query("SELECT * FROM agent_tasks WHERE enabled = 1 AND last_run_at IS NULL ORDER BY created_at ASC")
    suspend fun runnable(): List<AgentTaskEntity>

    @Query("SELECT * FROM agent_tasks WHERE enabled = 1 AND last_run_at IS NULL ORDER BY created_at ASC")
    fun runnableBlocking(): List<AgentTaskEntity>

    @Query("SELECT * FROM agent_tasks WHERE id = :id")
    suspend fun byId(id: String): AgentTaskEntity?

    @Upsert
    suspend fun upsert(task: AgentTaskEntity)

    @Upsert
    fun upsertBlocking(task: AgentTaskEntity)

    @Query("UPDATE agent_tasks SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean)

    /** 标记一次执行结果。第一版语义：跑过即完成（`last_run_at` 非空后不再被选中）。 */
    @Query("UPDATE agent_tasks SET last_run_at = :at, last_result = :result WHERE id = :id")
    suspend fun markRan(id: String, at: Long, result: String?)

    @Query("DELETE FROM agent_tasks WHERE id = :id")
    suspend fun delete(id: String)
}
