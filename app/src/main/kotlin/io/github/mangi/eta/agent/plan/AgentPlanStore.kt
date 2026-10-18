package io.github.mangi.eta.agent.plan

import io.github.mangi.eta.data.db.plan.ConversationTodoDao
import io.github.mangi.eta.data.db.plan.ConversationTodoEntity

/**
 * 任务清单的领域层读写入口。
 *
 * 这一层是 [AgentPlanItem]（纯模型）与 [ConversationTodoEntity]（Room 实体）之间唯一的转换点：
 * 上层永远只见模型，DAO 永远只见实体。这样做的好处是——**将来换存储（比如按项目分表）时，
 * 只需要改这一个文件**，工具层与 UI 层完全不受影响。
 *
 * 所有方法都是 `suspend`：Room 的 DAO 是挂起的，这里不做任何阻塞桥接，避免主线程风险。
 */
internal class AgentPlanStore(private val dao: ConversationTodoDao) {

    /** 读回清单。顺序即 [ConversationTodoEntity.position]，不在这里重排（顺序属于数据）。 */
    suspend fun load(conversationId: String): List<AgentPlanItem> {
        if (conversationId.isBlank()) return emptyList()
        return dao.list(conversationId).map { it.toItem() }
    }

    /**
     * 全量覆盖写入。
     *
     * 传入空列表等价于清空清单 —— 这是刻意的：模型说「这件事做完了，不需要清单」时，
     * 传空表比调用一个单独的删除工具更自然，也少一个工具要教。
     */
    suspend fun replace(conversationId: String, items: List<AgentPlanItem>, nowMillis: Long) {
        if (conversationId.isBlank()) return
        val entities = items.mapIndexed { index, item -> item.toEntity(conversationId, index, nowMillis) }
        dao.replaceAll(conversationId, entities)
    }

    /** 会话删除时的清理钩子；调用方负责在事务边界内调用。 */
    suspend fun clear(conversationId: String) {
        if (conversationId.isBlank()) return
        dao.deleteAll(conversationId)
    }

    /**
     * 同步读 —— 供工具层使用。
     *
     * 工具执行已运行在后台线程（AgentLocalTools 的调度约定），因此这里不提供 `runBlocking`
     * 包装：那会把「当前线程必须不是主线程」这个前提藏进调用点，日后挪动就会变成卡顿事故。
     */
    fun loadBlocking(conversationId: String): List<AgentPlanItem> {
        if (conversationId.isBlank()) return emptyList()
        return dao.listBlocking(conversationId).map { it.toItem() }
    }

    /** 同步全量覆盖 —— 供工具层使用，理由同 [loadBlocking]。 */
    fun replaceBlocking(conversationId: String, items: List<AgentPlanItem>, nowMillis: Long) {
        if (conversationId.isBlank()) return
        val entities = items.mapIndexed { index, item -> item.toEntity(conversationId, index, nowMillis) }
        dao.replaceAllBlocking(conversationId, entities)
    }

    private fun ConversationTodoEntity.toItem(): AgentPlanItem = AgentPlanItem(
        id = itemId,
        subject = subject,
        activeForm = activeForm,
        // 库里存的是字符串；解析失败时回退 PENDING 而不是抛异常——
        // 一条损坏的记录不该让整个清单读不出来（进度条宁可显示"待开始"）。
        status = AgentPlanStatus.fromWire(status) ?: AgentPlanStatus.PENDING,
    )

    private fun AgentPlanItem.toEntity(
        conversationId: String,
        position: Int,
        nowMillis: Long,
    ): ConversationTodoEntity = ConversationTodoEntity(
        conversationId = conversationId,
        itemId = id,
        subject = subject,
        activeForm = activeForm,
        status = status.wire,
        position = position,
        updatedAt = nowMillis,
    )
}
