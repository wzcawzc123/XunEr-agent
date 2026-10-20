package io.github.mangi.eta.agent.plan

import io.github.mangi.eta.data.db.plan.ConversationPlanDao
import io.github.mangi.eta.data.db.plan.ConversationPlanEntity
import io.github.mangi.eta.data.db.plan.ConversationTodoDao
import io.github.mangi.eta.data.db.plan.ConversationTodoEntity

/** 计划模式测试用的内存 DAO；与生产实现同语义，但不依赖 Room。 */
internal class FakePlanDao : ConversationPlanDao {
    val store = mutableMapOf<String, ConversationPlanEntity>()

    override suspend fun get(conversationId: String): ConversationPlanEntity? = store[conversationId]
    override fun getBlocking(conversationId: String): ConversationPlanEntity? = store[conversationId]
    override suspend fun upsert(plan: ConversationPlanEntity) {
        store[plan.conversationId] = plan
    }
    override fun upsertBlocking(plan: ConversationPlanEntity) {
        store[plan.conversationId] = plan
    }
    override suspend fun delete(conversationId: String) {
        store.remove(conversationId)
    }
    override fun deleteBlocking(conversationId: String) {
        store.remove(conversationId)
    }
    override suspend fun deleteAll() {
        store.clear()
    }
    override suspend fun listAll(): List<ConversationPlanEntity> = store.values.toList()
}

internal class FakeTodoDao : ConversationTodoDao {
    val store = mutableMapOf<String, List<ConversationTodoEntity>>()

    override suspend fun list(conversationId: String) = store[conversationId].orEmpty()
    override fun listBlocking(conversationId: String) = store[conversationId].orEmpty()
    override suspend fun upsert(items: List<ConversationTodoEntity>) = Unit
    override fun upsertBlocking(items: List<ConversationTodoEntity>) = Unit
    override suspend fun deleteAll(conversationId: String) {
        store.remove(conversationId)
    }
    override fun deleteAllBlocking(conversationId: String) {
        store.remove(conversationId)
    }
    override suspend fun replaceAll(conversationId: String, items: List<ConversationTodoEntity>) {
        store[conversationId] = items
    }
    override fun replaceAllBlocking(conversationId: String, items: List<ConversationTodoEntity>) {
        store[conversationId] = items
    }
    override suspend fun listAll(): List<ConversationTodoEntity> = store.values.flatten()
}
