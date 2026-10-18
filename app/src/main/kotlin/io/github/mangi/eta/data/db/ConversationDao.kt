package io.github.mangi.eta.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert

@Dao
internal interface ConversationDao : ChunkedTextDao {

    /**
     * 用量聚合的原始投影（Room 要求具名类，不能直接返回领域模型）。
     * 全部用 Long：真机单会话输入已达十亿级。
     */
    data class TokenUsageProjection(
        val turns: Int,
        val inputTokens: Long,
        val cachedTokens: Long,
        val outputTokens: Long,
        val reasoningTokens: Long,
    )

    /**
     * 按会话聚合 token 用量。
     *
     * 在 SQL 里聚合而不是把消息读到内存再累加 —— 大会话有上万条消息，
     * 只为算几个总数把整表读出来不值得。
     * `input_tokens > 0` 过滤掉本地占位/提示记录（它们从未发出请求）。
     * 全部 COALESCE：老数据里 cached/reasoning 可能是 NULL。
     */
    @Query(
        "SELECT COUNT(*) AS turns, " +
            "COALESCE(SUM(input_tokens), 0) AS inputTokens, " +
            "COALESCE(SUM(cached_tokens), 0) AS cachedTokens, " +
            "COALESCE(SUM(output_tokens), 0) AS outputTokens, " +
            "COALESCE(SUM(reasoning_tokens), 0) AS reasoningTokens " +
            "FROM conversation_messages " +
            "WHERE conversation_id = :conversationId AND input_tokens > 0",
    )
    fun tokenUsageFor(conversationId: String): TokenUsageProjection

    /** 全局聚合（所有会话）。用于「总账」展示。 */
    @Query(
        "SELECT COUNT(*) AS turns, " +
            "COALESCE(SUM(input_tokens), 0) AS inputTokens, " +
            "COALESCE(SUM(cached_tokens), 0) AS cachedTokens, " +
            "COALESCE(SUM(output_tokens), 0) AS outputTokens, " +
            "COALESCE(SUM(reasoning_tokens), 0) AS reasoningTokens " +
            "FROM conversation_messages WHERE input_tokens > 0",
    )
    fun tokenUsageAll(): TokenUsageProjection

    @Query(
        "SELECT id, title, thinking_enabled, reasoning_effort, " +
            "applied_runtime_run_ids_json, roleplay_json, revisions_json, created_at, updated_at, model_id " +
            "FROM conversations ORDER BY updated_at DESC"
    )
    suspend fun conversationMetadataRows(): List<ConversationMetadata>

    @Transaction
    suspend fun conversations(): List<ConversationMetadata> = conversationMetadataRows().map { restoreMetadata(it) }

    @Query(
        "SELECT id, title, thinking_enabled, reasoning_effort, " +
            "applied_runtime_run_ids_json, roleplay_json, revisions_json, created_at, updated_at, model_id " +
            "FROM conversations ORDER BY updated_at DESC LIMIT :limit OFFSET :offset"
    )
    suspend fun conversationMetadataPage(limit: Int, offset: Int): List<ConversationMetadata>

    @Transaction
    suspend fun conversationsPage(limit: Int, offset: Int): List<ConversationMetadata> =
        conversationMetadataPage(limit, offset).map { restoreMetadata(it) }

    suspend fun restoreMetadata(row: ConversationMetadata) = row.copy(
        appliedRuntimeRunIdsJson = restoreText("conversations", row.id, "runs", row.appliedRuntimeRunIdsJson),
        roleplayJson = restoreText("conversations", row.id, "roleplay", row.roleplayJson),
        revisionsJson = restoreText("conversations", row.id, "revisions", row.revisionsJson),
    )

    @Query("SELECT roleplay_json FROM conversations WHERE id = :conversationId")
    suspend fun roleplayJsonRow(conversationId: String): String?

    /** 下一条消息的 sort_index（自动任务追加自动轮时用）。 */
    @Query("SELECT COALESCE(MAX(sort_index), -1) + 1 FROM conversation_messages WHERE conversation_id = :conversationId")
    suspend fun nextSortIndex(conversationId: String): Int

    /** 触达会话的 updated_at，让它在会话列表里冒到前面。 */
    @Query("UPDATE conversations SET updated_at = :at WHERE id = :conversationId")
    suspend fun touchConversation(conversationId: String, at: Long)

    /** 只取历史 JSON：自动任务执行时需要在原会话追加一轮，要先读回上下文。 */
    @Query("SELECT history_json FROM conversations WHERE id = :conversationId")
    suspend fun historyJsonFor(conversationId: String): String?

    @Transaction
    suspend fun roleplayJson(conversationId: String): String? = roleplayJsonRow(conversationId)?.let {
        restoreText("conversations", conversationId, "roleplay", it)
    }

    @Query("SELECT * FROM conversation_messages ORDER BY conversation_id ASC, sort_index ASC")
    suspend fun messageRows(): List<ConversationMessageEntity>

    @Transaction
    suspend fun messages(): List<ConversationMessageEntity> = messageRows().map { restoreMessage(it) }

    @Query("SELECT * FROM conversations ORDER BY updated_at ASC")
    suspend fun conversationEntityRows(): List<ConversationEntity>

    @Transaction
    suspend fun conversationEntities(): List<ConversationEntity> = conversationEntityRows().map { row ->
        restoreConversation(row)
    }

    @Query("SELECT * FROM conversations ORDER BY updated_at ASC, id ASC LIMIT :limit OFFSET :offset")
    suspend fun conversationEntityRowsPage(limit: Int, offset: Int): List<ConversationEntity>

    suspend fun restoreConversation(row: ConversationEntity) = row.copy(
        appliedRuntimeRunIdsJson = restoreText("conversations", row.id, "runs", row.appliedRuntimeRunIdsJson),
        roleplayJson = restoreText("conversations", row.id, "roleplay", row.roleplayJson),
        revisionsJson = restoreText("conversations", row.id, "revisions", row.revisionsJson),
    )

    @Query("SELECT * FROM conversation_messages ORDER BY conversation_id ASC, sort_index ASC LIMIT :limit OFFSET :offset")
    suspend fun allMessageRowsPage(limit: Int, offset: Int): List<ConversationMessageEntity>

    @Query("SELECT * FROM conversation_context_checkpoints ORDER BY conversation_id ASC")
    suspend fun contextCheckpointRows(): List<ConversationContextCheckpointEntity>

    @Transaction
    suspend fun contextCheckpoints(): List<ConversationContextCheckpointEntity> = contextCheckpointRows().map { restoreCheckpoint(it) }

    @Query("SELECT * FROM conversation_context_checkpoints ORDER BY conversation_id ASC LIMIT :limit OFFSET :offset")
    suspend fun contextCheckpointRowsPage(limit: Int, offset: Int): List<ConversationContextCheckpointEntity>

    @Query("SELECT * FROM conversation_messages WHERE conversation_id = :conversationId ORDER BY sort_index ASC LIMIT :limit OFFSET :offset")
    suspend fun messageRowsPage(conversationId: String, limit: Int, offset: Int): List<ConversationMessageEntity>

    @Transaction
    suspend fun messagesPage(conversationId: String, limit: Int, offset: Int): List<ConversationMessageEntity> =
        messageRowsPage(conversationId, limit, offset).map { restoreMessage(it) }

    @Query("SELECT COUNT(*) FROM conversation_messages WHERE conversation_id = :conversationId")
    suspend fun messageCount(conversationId: String): Int

    @Query("SELECT EXISTS(SELECT 1 FROM conversation_messages WHERE conversation_id = :conversationId AND id = :messageId AND type = 'assistant')")
    suspend fun hasAssistantMessage(conversationId: String, messageId: String): Boolean

    @Query("SELECT * FROM conversation_context_checkpoints WHERE conversation_id = :conversationId")
    suspend fun contextCheckpointRow(conversationId: String): ConversationContextCheckpointEntity?

    @Transaction
    suspend fun contextCheckpoint(conversationId: String): ConversationContextCheckpointEntity? =
        contextCheckpointRow(conversationId)?.let { restoreCheckpoint(it) }

    suspend fun restoreCheckpoint(row: ConversationContextCheckpointEntity) = row.copy(
        historyJson = restoreText("conversation_context_checkpoints", row.conversationId, "history", row.historyJson),
        journalJson = restoreText("conversation_context_checkpoints", row.conversationId, "journal", row.journalJson),
    )

    @Query("SELECT * FROM conversation_state WHERE id = :id")
    suspend fun state(id: String = ConversationStateEntity.SINGLETON_ID): ConversationStateEntity?

    @Upsert
    suspend fun insertConversationRow(conversation: ConversationEntity)

    @Transaction
    suspend fun insertConversations(conversations: List<ConversationEntity>) {
        conversations.forEach { row ->
            insertConversationRow(row.copy(
                appliedRuntimeRunIdsJson = storeText("conversations", row.id, "runs", row.appliedRuntimeRunIdsJson),
                roleplayJson = storeText("conversations", row.id, "roleplay", row.roleplayJson),
                revisionsJson = storeText("conversations", row.id, "revisions", row.revisionsJson),
            ))
        }
    }

    @Upsert
    suspend fun insertMessageRow(message: ConversationMessageEntity)

    @Transaction
    suspend fun insertMessages(messages: List<ConversationMessageEntity>) {
        messages.forEach { row ->
            insertMessageRow(row.copy(
                content = storeText("conversation_messages", row.id, "content", row.content),
                imagesJson = storeText("conversation_messages", row.id, "images", row.imagesJson),
            ))
        }
    }

    suspend fun restoreMessage(row: ConversationMessageEntity) = row.copy(
        content = restoreText("conversation_messages", row.id, "content", row.content),
        imagesJson = restoreText("conversation_messages", row.id, "images", row.imagesJson),
    )

    @Upsert
    suspend fun insertContextCheckpointRow(checkpoint: ConversationContextCheckpointEntity)

    @Transaction
    suspend fun insertContextCheckpoints(checkpoints: List<ConversationContextCheckpointEntity>) {
        checkpoints.forEach { row ->
            insertContextCheckpointRow(row.copy(
                historyJson = storeText("conversation_context_checkpoints", row.conversationId, "history", row.historyJson),
                journalJson = storeText("conversation_context_checkpoints", row.conversationId, "journal", row.journalJson),
            ))
        }
    }

    @Transaction
    suspend fun updateContextCheckpoint(conversationId: String, historyJson: String?, journalJson: String?) {
        val previous = contextCheckpointRow(conversationId)
            ?: ConversationContextCheckpointEntity(conversationId, "[]")
        insertContextCheckpointRow(previous.copy(
            historyJson = historyJson?.let { storeText("conversation_context_checkpoints", conversationId, "history", it) }
                ?: previous.historyJson,
            journalJson = journalJson?.let { storeText("conversation_context_checkpoints", conversationId, "journal", it) }
                ?: previous.journalJson,
        ))
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertState(state: ConversationStateEntity)

    @Query("DELETE FROM conversations")
    suspend fun deleteConversations()

    @Query("DELETE FROM conversations WHERE id = :conversationId")
    suspend fun deleteConversation(conversationId: String)

    @Query("DELETE FROM conversation_messages")
    suspend fun deleteMessages()

    @Query("DELETE FROM conversation_messages WHERE conversation_id = :conversationId AND sort_index >= :fromIndex")
    suspend fun deleteMessagesFrom(conversationId: String, fromIndex: Int)

    @Query("DELETE FROM conversation_context_checkpoints")
    suspend fun deleteContextCheckpoints()

    @Query("DELETE FROM conversation_state")
    suspend fun deleteState()

    @Transaction
    suspend fun replaceAll(
        conversations: List<ConversationEntity>,
        messages: List<ConversationMessageEntity>,
        contextCheckpoints: List<ConversationContextCheckpointEntity> = emptyList(),
        state: ConversationStateEntity?,
    ) {
        deleteMessages()
        deleteContextCheckpoints()
        deleteConversations()
        deleteState()
        insertConversations(conversations)
        insertContextCheckpoints(contextCheckpoints)
        insertMessages(messages)
        state?.let { insertState(it) }
    }
}
