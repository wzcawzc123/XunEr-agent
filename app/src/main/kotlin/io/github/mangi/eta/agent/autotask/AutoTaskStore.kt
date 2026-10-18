package io.github.mangi.eta.agent.autotask

import io.github.mangi.eta.data.db.autotask.AgentTaskDao
import io.github.mangi.eta.data.db.autotask.AgentTaskEntity

/**
 * 自动任务的领域层读写入口。
 *
 * 与 `AgentPlanStore` 同样的分工：**这里是模型与实体唯一的转换点**。
 * 上层只见 [AgentTask]，DAO 只见实体；将来换存储只改这一个文件。
 *
 * 条件用位掩码持久化（1=充电 2=WiFi 4=灭屏）。选位掩码而不是三个布尔列：
 * 后续加"蓝牙/位置"等条件时只扩位定义，不改表结构。
 */
internal class AutoTaskStore(private val dao: AgentTaskDao) {

    suspend fun all(): List<AgentTask> = dao.all().map { it.toTask() }

    fun allBlocking(): List<AgentTask> = dao.allBlocking().map { it.toTask() }

    /** 只取"已启用且尚未执行"的任务；条件判定由调用方用设备状态完成。 */
    suspend fun runnable(): List<AgentTask> = dao.runnable().map { it.toTask() }

    fun runnableBlocking(): List<AgentTask> = dao.runnableBlocking().map { it.toTask() }

    suspend fun byId(id: String): AgentTask? = dao.byId(id)?.toTask()

    suspend fun save(task: AgentTask) = dao.upsert(task.toEntity())

    fun saveBlocking(task: AgentTask) = dao.upsertBlocking(task.toEntity())

    suspend fun setEnabled(id: String, enabled: Boolean) = dao.setEnabled(id, enabled)

    /** 标记执行结果。第一版语义：跑过即完成，之后不再被选中。 */
    suspend fun markRan(id: String, at: Long, result: String?) = dao.markRan(id, at, result)

    suspend fun delete(id: String) = dao.delete(id)

    /**
     * 把当前任务集导出给 root 守护脚本（写 pending.txt）。
     *
     * 为什么放在 Store 而不是调用方：导出是「数据库 → 文件」的投影，
     * 和读写数据库是同一件事。之前写在 AgentLocalTools 里，导致
     * ① 只能靠工具调用时触发，其他路径改了任务不会同步；
     * ② 里面还手工重建了一份 Entity→域模型 的映射，漏了 active 字段。
     *
     * 失败静默：导出失败不应该影响任务本身的保存。
     */
    fun exportTo(context: android.content.Context) {
        runCatching {
            AutoTaskExport.export(
                context = context,
                tasks = allBlocking(),
                nowMillis = System.currentTimeMillis(),
            )
        }
    }

    // ---------- 转换 ----------

    /**
     * Entity → 域模型。
     *
     * **internal 而不是 private**：导出给守护脚本时也要用这同一份映射。
     * 之前 AgentLocalTools 里手工重建了一份，漏了 `active` 字段 ——
     * 导致「一次性任务执行后不再触发」这个修复被静默抵消。
     * 映射只允许有一份。
     */
    internal fun AgentTaskEntity.toTask(): AgentTask = AgentTask(
        id = id,
        title = title,
        prompt = prompt,
        condition = TaskCondition.fromMask(conditionMask),
        maxRounds = maxRounds,
        enabled = enabled,
        readOnly = readOnly,
        conversationId = conversationId,
        active = active,
        lastRunAt = lastRunAt,
        lastResult = lastResult,
        createdAt = createdAt,
    )

    private fun AgentTask.toEntity(): AgentTaskEntity = AgentTaskEntity(
        id = id,
        title = title,
        prompt = prompt,
        conditionMask = condition.toMask(),
        maxRounds = maxRounds,
        enabled = enabled,
        readOnly = readOnly,
        conversationId = conversationId,
        active = active,
        lastRunAt = lastRunAt,
        lastResult = lastResult,
        createdAt = createdAt,
    )
}
