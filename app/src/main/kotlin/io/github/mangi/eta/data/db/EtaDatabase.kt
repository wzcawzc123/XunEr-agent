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
         * 数据库版本。
         *
         * 三条血统各自占用过一批版本号，所以这里必须比它们都大：
         * - 上游：23（3.4.0）→ 24（会话置顶）→ 25（性能统计列）
         * - 旧 fork 线 4.0.x–4.2.0：24–31（同一批号码含义完全不同）
         * - 我们 4.3.x：32
         *
         * 同一个号码在两条血统上含义不同，只能往上加、不能回退；低版本号一律走
         * [convergeToCurrentSchema] 收敛（幂等，对任何血统都安全）。
         */
        internal const val SCHEMA_VERSION = 33

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

        internal val MIGRATION_22_23 = Migration(22, 23) { database ->
            database.execSQL("ALTER TABLE model_providers ADD COLUMN auth_mode TEXT NOT NULL DEFAULT 'api_key'")
        }

        /**
         * 上游血统（23）→ 当前版本：结构本来就一致，什么都不用做。
         *
         * 这是「刚装完 4.3.0、再升 4.3.1」的路径，以及任何仍停留在上游 3.4.0 的设备。
         */
        internal val MIGRATION_23_33 = upgradeSchemaToLatest(23)

        /**
         * 旧 fork 血统（24–31）→ 当前版本。
         *
         * fork 的库结构与上游有三处偏差，全部在这里收敛掉（与 4.2.0 自己的
         * `applyLatestSchemaDelta` 完全同源，因为它当年就是这么把库调回上游形态的）：
         * - 三张 fork 独有表；DROP 掉，Room 校验时不会因为「多出来的表」失败
         * - 老版本没有 auth_mode 列（22→23 才加），用存在性检查补齐，重复执行也安全
         *
         * 之所以要覆盖 24–31 每一个号：Room 找不到迁移路径时会走
         * `fallbackToDestructiveMigration` → **静默清库**。那比装不上更糟。
         */
        /** 列是否存在（PRAGMA table_info）；用于幂等地补列。 */
        private fun androidx.sqlite.db.SupportSQLiteDatabase.hasColumn(table: String, column: String): Boolean =
            query("PRAGMA table_info($table)").use { cursor ->
                var found = false
                while (cursor.moveToNext()) {
                    if (cursor.getString(1) == column) {
                        found = true
                        break
                    }
                }
                found
            }

        private fun androidx.sqlite.db.SupportSQLiteDatabase.addColumnIfMissing(
            table: String,
            column: String,
            definition: String,
        ) {
            if (!hasColumn(table, column)) execSQL("ALTER TABLE $table ADD COLUMN $column $definition")
        }

        /**
         * 幂等 schema 收敛器：把任意历史血统的库对齐到当前结构。
         *
         * 三处差异全部用存在性检查处理，重复执行安全：
         * - fork 自建的三张表（旧线有、我们和上游都没有）
         * - `auth_mode`（上游 23 才加，fork 早期版本缺）
         * - `is_pinned` 与 7 个性能统计列（上游 24/25 才加，fork 与我们 4.3.x 都缺）
         *
         * 不做这一步的后果不是报错而是**静默清库**：Room 找不到迁移路径时会走
         * `fallbackToDestructiveMigration`，用户升级后数据凭空消失。
         */
        private fun androidx.sqlite.db.SupportSQLiteDatabase.convergeToCurrentSchema() {
            execSQL("DROP TABLE IF EXISTS conversation_todos")
            execSQL("DROP TABLE IF EXISTS agent_tasks")
            execSQL("DROP TABLE IF EXISTS conversation_plans")
            val hasAuthMode = query("PRAGMA table_info(model_providers)").use { cursor ->
                var found = false
                while (cursor.moveToNext()) {
                    if (cursor.getString(1) == "auth_mode") {
                        found = true
                        break
                    }
                }
                found
            }
            if (!hasAuthMode) {
                execSQL("ALTER TABLE model_providers ADD COLUMN auth_mode TEXT NOT NULL DEFAULT 'api_key'")
            }
            // 上游 24：会话置顶
            addColumnIfMissing("conversations", "is_pinned", "INTEGER NOT NULL DEFAULT 0")
            // 上游 25：单轮性能统计（TTFT / 模型用时 / 工具用时 / 输出速度）
            addColumnIfMissing("conversation_messages", "step_count", "INTEGER")
            addColumnIfMissing("conversation_messages", "llm_ms", "INTEGER")
            addColumnIfMissing("conversation_messages", "tool_ms", "INTEGER")
            addColumnIfMissing("conversation_messages", "ttft_ms", "INTEGER")
            addColumnIfMissing("conversation_messages", "ttft_steps", "INTEGER")
            addColumnIfMissing("conversation_messages", "decode_ms", "INTEGER")
            addColumnIfMissing("conversation_messages", "decode_tokens", "INTEGER")
        }

        internal fun upgradeSchemaToLatest(from: Int): Migration =
            Migration(from, SCHEMA_VERSION) { database -> database.convergeToCurrentSchema() }

        internal val MIGRATION_24_33 = upgradeSchemaToLatest(24)
        internal val MIGRATION_25_33 = upgradeSchemaToLatest(25)
        internal val MIGRATION_26_33 = upgradeSchemaToLatest(26)
        internal val MIGRATION_27_33 = upgradeSchemaToLatest(27)
        internal val MIGRATION_28_33 = upgradeSchemaToLatest(28)
        internal val MIGRATION_29_33 = upgradeSchemaToLatest(29)
        internal val MIGRATION_30_33 = upgradeSchemaToLatest(30)
        internal val MIGRATION_31_33 = upgradeSchemaToLatest(31)
        internal val MIGRATION_32_33 = upgradeSchemaToLatest(32)

        /**
         * 全部迁移（单一来源）。
         *
         * 收在这里而不是内联在 `addMigrations(...)` 里，是为了让守卫测试能对它断言：
         * 少了任一版本号的迁移，Room 会走 `fallbackToDestructiveMigration` **静默清库**，
         * 而这种问题在开发期完全看不出来（新装用户的库版本永远是最新的）。
         *
         * by lazy：各 MIGRATION_* 定义分散在本 companion 各处，避免初始化顺序耦合。
         */
        internal val MIGRATIONS: List<Migration> by lazy {
            listOf(
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
                MIGRATION_22_23,
                MIGRATION_23_33,
                MIGRATION_24_33,
                MIGRATION_25_33,
                MIGRATION_26_33,
                MIGRATION_27_33,
                MIGRATION_28_33,
                MIGRATION_29_33,
                MIGRATION_30_33,
                MIGRATION_31_33,
                MIGRATION_32_33,
            )
        }

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
