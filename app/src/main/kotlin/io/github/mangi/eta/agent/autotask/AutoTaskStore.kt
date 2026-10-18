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

    // ---------- 转换 ----------

    private fun AgentTaskEntity.toTask(): AgentTask = AgentTask(
        id = id,
        title = title,
        prompt = prompt,
        condition = TaskCondition.fromMask(conditionMask),
        maxRounds = maxRounds,
        enabled = enabled,
        readOnly = readOnly,
        conversationId = conversationId,
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
        lastRunAt = lastRunAt,
        lastResult = lastResult,
        createdAt = createdAt,
    )
}
