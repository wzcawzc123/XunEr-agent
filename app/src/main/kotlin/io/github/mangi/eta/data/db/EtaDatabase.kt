package io.github.mangi.eta.data.db

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration

@Database(
    entities = [
        AgentTextChunkEntity::class,
        ConversationEntity::class,
        ConversationContextCheckpointEntity::class,
        ConversationMessageEntity::class,
        ConversationStateEntity::class,
        ProviderEntity::class,
        ProviderModelEntity::class,
        RuntimeResultEntity::class,
        RuntimeArchiveRunEntity::class,
        RuntimeArchiveEventEntity::class,
        RuntimeInFlightRunEntity::class,
        RuntimeInFlightEventEntity::class,
        SkillRegistryEntity::class,
        McpServerEntity::class,
        CharacterEntity::class,
        UserPersonaEntity::class,
    ],
    version = EtaDatabase.SCHEMA_VERSION,
    exportSchema = false,
)
internal abstract class EtaDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun providerDao(): ProviderDao
    abstract fun runtimeRunDao(): RuntimeRunDao
    abstract fun skillDao(): SkillDao
    abstract fun mcpServerDao(): McpServerDao
    abstract fun characterDao(): CharacterDao

    companion object {
        /**
         * 单一来源：`@Database(version = ...)` 与迁移链的终点都以它为准。
         *
         * v31（合并上游 v23 后）：版本号必须**高于所有血统已发布过的版本**——
         * 旧 fork 3.14.1 到 29、重建版 4.0.0 到 30、上游到 23。
         * 低于任一条线的已发布版本，覆盖安装时 Room 视为降级，
         * `fallbackToDestructiveMigration` 会直接清空全部表
         * （2026-10-22 真机升级实测：3.14.1 的会话/角色被清空，用户察觉到「像清了数据」）。
         * 22-30 各版本到 31 的迁移见 [upgradeToLatest]（幂等，兼容各血统）。
         */
        internal const val SCHEMA_VERSION = 31

        @Volatile
        private var instance: EtaDatabase? = null

        fun get(context: Context): EtaDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    EtaDatabase::class.java,
                    "eta.db",
                )
                    .addMigrations(*MIGRATIONS.toTypedArray())
                    .addCallback(object : Callback() {
                        override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) { createTextChunkCleanup(db) }
                        override fun onOpen(db: androidx.sqlite.db.SupportSQLiteDatabase) { createTextChunkCleanup(db) }
                    })
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { instance = it }
            }

        @VisibleForTesting
        internal fun closeForTests() {
            synchronized(this) {
                instance?.close()
                instance = null
            }
        }

        internal val MIGRATION_19_20 = Migration(19, 20) { database ->
            database.execSQL("ALTER TABLE conversation_context_checkpoints ADD COLUMN journal_json TEXT NOT NULL DEFAULT ''")
            database.execSQL("ALTER TABLE runtime_inflight_runs ADD COLUMN transcript_json TEXT NOT NULL DEFAULT '[]'")
            database.execSQL("CREATE TABLE IF NOT EXISTS agent_text_chunks (" +
                "owner_table TEXT NOT NULL, owner_id TEXT NOT NULL, field TEXT NOT NULL, " +
                "chunk_index INTEGER NOT NULL, content TEXT NOT NULL, " +
                "PRIMARY KEY(owner_table, owner_id, field, chunk_index))")
            database.execSQL("UPDATE conversation_context_checkpoints SET journal_json = history_json")
            HistoryPayloadMigration.migrate(database)
            createTextChunkCleanup(database)
        }

        internal val MIGRATION_20_21 = Migration(20, 21) { database ->
            database.execSQL("ALTER TABLE conversations ADD COLUMN roleplay_json TEXT NOT NULL DEFAULT ''")
            database.execSQL("ALTER TABLE conversations ADD COLUMN revisions_json TEXT NOT NULL DEFAULT ''")
            listOf("runtime_results", "runtime_archive_runs", "runtime_inflight_runs").forEach { table ->
                database.execSQL("ALTER TABLE $table ADD COLUMN rewrite_target_message_id TEXT")
            }
            database.execSQL("CREATE TABLE IF NOT EXISTS roleplay_characters (" +
                "id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, card_json TEXT NOT NULL, " +
                "avatar_path TEXT, archived INTEGER NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)")
            database.execSQL("CREATE TABLE IF NOT EXISTS roleplay_user_persona (" +
                "id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, description TEXT NOT NULL)")
            createTextChunkCleanup(database)
        }

        internal val MIGRATION_21_22 = Migration(21, 22) { database ->
            database.execSQL("ALTER TABLE conversations ADD COLUMN model_id TEXT")
        }

        /**
         * 升级到 [SCHEMA_VERSION] 的统一迁移体（22..30 各版本一步直达）。
         *
         * 幂等设计的原因：同一个版本号在两条血统上含义不同——
         * 旧 fork 23 有 todos/tasks/plans 三表、没有 auth_mode 列；
         * 上游 23 有 auth_mode 列、没有三表。用存在性检查兼容两边，
         * 重复 ALTER 或 DROP 都不会炸。合并上游 v23（订阅登录的 auth_mode 列）后
         * 版本号必须 > 30（4.0.0 已发布过 version=30，同版本号换 schema 会被 Room 判损坏）。
         */
        private fun androidx.sqlite.db.SupportSQLiteDatabase.applyLatestSchemaDelta() {
            execSQL("DROP TABLE IF EXISTS conversation_todos")
            execSQL("DROP TABLE IF EXISTS agent_tasks")
            execSQL("DROP TABLE IF EXISTS conversation_plans")
            val hasAuthMode = query("PRAGMA table_info(model_providers)").use { cursor ->
                var found = false
                while (cursor.moveToNext()) {
                    if (cursor.getString(1) == "auth_mode") { found = true; break }
                }
                found
            }
            if (!hasAuthMode) {
                execSQL("ALTER TABLE model_providers ADD COLUMN auth_mode TEXT NOT NULL DEFAULT 'api_key'")
            }
        }

        internal fun upgradeToLatest(from: Int): Migration = Migration(from, SCHEMA_VERSION) {
            it.applyLatestSchemaDelta()
        }

        internal val MIGRATION_22_31 = upgradeToLatest(22)
        internal val MIGRATION_23_31 = upgradeToLatest(23)
        internal val MIGRATION_24_31 = upgradeToLatest(24)
        internal val MIGRATION_25_31 = upgradeToLatest(25)
        internal val MIGRATION_26_31 = upgradeToLatest(26)
        internal val MIGRATION_27_31 = upgradeToLatest(27)
        internal val MIGRATION_28_31 = upgradeToLatest(28)
        internal val MIGRATION_29_31 = upgradeToLatest(29)
        internal val MIGRATION_30_31 = upgradeToLatest(30)

        /**
         * 全部迁移（单一来源）：`addMigrations` 与迁移链守卫测试都用它。
         * 主干 6→…→22 连续（上游底座）；22-30 各有一步直达 [SCHEMA_VERSION]。
         * by lazy：各 MIGRATION_* 定义分散在本 companion 各处，避免初始化顺序耦合。
         */
        internal val MIGRATIONS: List<Migration> by lazy { listOf(
            MIGRATION_6_7,
            MIGRATION_7_8,
            MIGRATION_8_9,
            MIGRATION_9_10,
            MIGRATION_10_11,
            MIGRATION_11_12,
            MIGRATION_12_13,
            MIGRATION_13_14,
            MIGRATION_14_15,
            MIGRATION_15_16,
            MIGRATION_16_17,
            MIGRATION_17_18,
            MIGRATION_18_19,
            MIGRATION_19_20,
            MIGRATION_20_21,
            MIGRATION_21_22,
            MIGRATION_22_31,
            MIGRATION_23_31,
            MIGRATION_24_31,
            MIGRATION_25_31,
            MIGRATION_26_31,
            MIGRATION_27_31,
            MIGRATION_28_31,
            MIGRATION_29_31,
            MIGRATION_30_31,
        ) }
        private fun createTextChunkCleanup(database: androidx.sqlite.db.SupportSQLiteDatabase) {
            mapOf("runtime_results" to "run_id", "runtime_archive_runs" to "archive_run_id",
                "runtime_inflight_runs" to "run_id", "conversation_context_checkpoints" to "conversation_id",
                "conversation_messages" to "id", "conversations" to "id")
                .plus(if (database.version >= 21 || database.query(
                    "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'roleplay_characters'"
                ).use { it.moveToFirst() }) mapOf("roleplay_characters" to "id", "roleplay_user_persona" to "id") else emptyMap())
                .forEach { (table, key) ->
                    database.execSQL("CREATE TRIGGER IF NOT EXISTS ${table}_text_cleanup AFTER DELETE ON $table " +
                        "BEGIN DELETE FROM agent_text_chunks WHERE owner_table = '$table' AND owner_id = OLD.$key; END")
                }
        }

        internal val MIGRATION_18_19 = Migration(18, 19) { database ->
            listOf("runtime_results", "runtime_archive_runs", "runtime_inflight_runs").forEach { table ->
                database.execSQL("ALTER TABLE $table ADD COLUMN context_snapshot_json TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE $table ADD COLUMN operation TEXT NOT NULL DEFAULT 'chat'")
            }
        }

        internal val MIGRATION_6_7 = Migration(6, 7) { database ->
            database.execSQL(
                "ALTER TABLE runtime_results ADD COLUMN transcript_json TEXT NOT NULL DEFAULT '[]'"
            )
            database.execSQL(
                "ALTER TABLE runtime_archive_runs ADD COLUMN transcript_json TEXT NOT NULL DEFAULT '[]'"
            )
        }

        internal val MIGRATION_16_17 = Migration(16, 17) { database ->
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS mcp_servers (" +
                    "id TEXT NOT NULL, " +
                    "name TEXT NOT NULL, " +
                    "url TEXT NOT NULL, " +
                    "enabled INTEGER NOT NULL, " +
                    "protocol_mode TEXT NOT NULL, " +
                    "authorization_type TEXT NOT NULL, " +
                    "tools_json TEXT NOT NULL, " +
                    "enabled_tool_names_json TEXT NOT NULL, " +
                    "created_at INTEGER NOT NULL, " +
                    "sort_order INTEGER NOT NULL, " +
                    "last_refreshed_at INTEGER, " +
                    "last_protocol_version TEXT, " +
                    "PRIMARY KEY(id))"
            )
        }

        internal val MIGRATION_17_18 = Migration(17, 18) { database ->
            database.execSQL("ALTER TABLE mcp_servers ADD COLUMN tools_expire_at INTEGER")
        }

        internal val MIGRATION_7_8 = Migration(7, 8) { database ->
            database.execSQL(
                "ALTER TABLE conversations ADD COLUMN " +
                    "applied_runtime_run_ids_json TEXT NOT NULL DEFAULT '[]'"
            )
        }

        internal val MIGRATION_8_9 = Migration(8, 9) { database ->
            database.execSQL(
                "ALTER TABLE provider_models ADD COLUMN source TEXT NOT NULL DEFAULT 'manual'"
            )
            database.execSQL(
                "UPDATE provider_models SET source = 'catalog' WHERE is_built_in = 1"
            )
            // 旧版“添加自定义模型”会在打开编辑框时提前落下一条空记录。
            database.execSQL("DELETE FROM provider_models WHERE TRIM(model_id) = ''")
            // 只清理由旧版“新建对话”产生、且用户从未真正使用或命名过的占位记录。
            database.execSQL(
                "DELETE FROM conversations " +
                    "WHERE title = '新对话' " +
                    "AND TRIM(history_json) = '[]' " +
                    "AND TRIM(applied_runtime_run_ids_json) = '[]' " +
                    "AND NOT EXISTS (" +
                    "SELECT 1 FROM conversation_messages " +
                    "WHERE conversation_messages.conversation_id = conversations.id)"
            )
            database.execSQL(
                "DELETE FROM conversation_state WHERE selected_conversation_id NOT IN " +
                    "(SELECT id FROM conversations)"
            )
        }

        internal val MIGRATION_9_10 = Migration(9, 10) { database ->
            database.execSQL(
                "ALTER TABLE conversations ADD COLUMN " +
                    "reasoning_effort TEXT NOT NULL DEFAULT 'default'"
            )
            database.execSQL(
                "UPDATE conversations SET reasoning_effort = " +
                    "CASE WHEN thinking_enabled = 1 THEN 'default' ELSE 'off' END"
            )
            database.execSQL(
                "ALTER TABLE provider_models ADD COLUMN " +
                    "reasoning_capabilities_json TEXT NOT NULL DEFAULT 'null'"
            )
        }

        internal val MIGRATION_10_11 = Migration(10, 11) { database ->
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS conversation_context_checkpoints (" +
                    "conversation_id TEXT NOT NULL, " +
                    "history_json TEXT NOT NULL, " +
                    "PRIMARY KEY(conversation_id), " +
                    "FOREIGN KEY(conversation_id) REFERENCES conversations(id) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE)"
            )
            database.execSQL(
                "INSERT INTO conversation_context_checkpoints (conversation_id, history_json) " +
                    "SELECT id, history_json FROM conversations"
            )
            // SQL 内搬移完整正文；后续分块迁移负责行大小，不能因旧字段过大丢弃历史。
            database.execSQL("UPDATE conversations SET history_json = '[]'")
        }

        internal val MIGRATION_11_12 = Migration(11, 12) { database ->
            database.execSQL(
                "ALTER TABLE conversation_messages ADD COLUMN " +
                    "is_edited INTEGER NOT NULL DEFAULT 0"
            )
        }

        internal val MIGRATION_12_13 = Migration(12, 13) { database ->
            database.execSQL(
                "ALTER TABLE model_providers ADD COLUMN " +
                    "hosted_web_search_enabled INTEGER NOT NULL DEFAULT 0"
            )
        }

        internal val MIGRATION_13_14 = Migration(13, 14) { database ->
            database.execSQL(
                "ALTER TABLE runtime_archive_runs ADD COLUMN " +
                    "user_image_previews_json TEXT NOT NULL DEFAULT '[]'"
            )
        }

        internal val MIGRATION_14_15 = Migration(14, 15) { database ->
            database.execSQL(
                "ALTER TABLE provider_models ADD COLUMN context_window_override INTEGER"
            )
            database.execSQL(
                "ALTER TABLE provider_models ADD COLUMN reasoning_override INTEGER"
            )
            database.execSQL(
                "ALTER TABLE provider_models ADD COLUMN " +
                    "reasoning_capabilities_override_json TEXT NOT NULL DEFAULT 'null'"
            )
        }

        internal val MIGRATION_15_16 = Migration(15, 16) { database ->
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS runtime_inflight_runs (" +
                    "run_id TEXT NOT NULL, " +
                    "owner_instance_id TEXT NOT NULL, " +
                    "handoff_id TEXT NOT NULL, " +
                    "handoff_source TEXT NOT NULL, " +
                    "handoff_payload TEXT NOT NULL, " +
                    "dismiss_entry_surface INTEGER NOT NULL, " +
                    "created_at INTEGER NOT NULL, " +
                    "updated_at INTEGER NOT NULL, " +
                    "PRIMARY KEY(run_id))"
            )
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS runtime_inflight_events (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "run_id TEXT NOT NULL, " +
                    "sort_index INTEGER NOT NULL, " +
                    "event_json TEXT NOT NULL, " +
                    "FOREIGN KEY(run_id) REFERENCES runtime_inflight_runs(run_id) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE)"
            )
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS index_runtime_inflight_events_run_id " +
                    "ON runtime_inflight_events(run_id)"
            )
            database.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS " +
                    "index_runtime_inflight_events_run_id_sort_index " +
                    "ON runtime_inflight_events(run_id, sort_index)"
            )
        }
    }
}
