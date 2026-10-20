package io.github.mangi.eta.data.db

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import io.github.mangi.eta.data.db.autotask.AgentTaskDao
import io.github.mangi.eta.data.db.autotask.AgentTaskEntity
import io.github.mangi.eta.data.db.plan.ConversationPlanDao
import io.github.mangi.eta.data.db.plan.ConversationPlanEntity
import io.github.mangi.eta.data.db.plan.ConversationTodoDao
import io.github.mangi.eta.data.db.plan.ConversationTodoEntity

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
        ConversationTodoEntity::class,
        ConversationPlanEntity::class,
        AgentTaskEntity::class,
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
    abstract fun conversationTodoDao(): ConversationTodoDao
    abstract fun conversationPlanDao(): ConversationPlanDao
    abstract fun agentTaskDao(): AgentTaskDao

    companion object {
        /**
         * 当前 schema 版本。
         *
         * 单一来源：`@Database(version = ...)` 与 [MIGRATIONS] 的末尾版本都以它为准，
         * `EtaDatabaseMigrationTest` 会断言两者首尾相接 —— 加版本号却漏写迁移会立刻变红，
         * 而不是等到线上 `fallbackToDestructiveMigration` 把用户数据清空。
         */
        internal const val SCHEMA_VERSION = 29


        @Volatile
        private var instance: EtaDatabase? = null

        fun get(context: Context): EtaDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    EtaDatabase::class.java,
                    "eta.db",
                )
                    .addMigrations(*DATABASE_MIGRATIONS)
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
         * 任务清单独立成表（v23）。
         *
         * 刻意**不**并入 conversation_context_checkpoints：检查点会被上下文压缩重写，
         * 而清单的全部价值恰恰是「压缩之后仍然记得自己在做什么」。
         * 独立表让它天然免疫压缩，代价只是一次额外查询。
         */
        internal val MIGRATION_22_23 = Migration(22, 23) { database ->
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS conversation_todos (" +
                    "conversation_id TEXT NOT NULL, " +
                    "item_id TEXT NOT NULL, " +
                    "subject TEXT NOT NULL, " +
                    "active_form TEXT NOT NULL, " +
                    "status TEXT NOT NULL, " +
                    "position INTEGER NOT NULL, " +
                    "updated_at INTEGER NOT NULL, " +
                    "PRIMARY KEY(conversation_id, item_id))",
            )
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS index_conversation_todos_conversation_id_position " +
                    "ON conversation_todos (conversation_id, position)",
            )
        }

        /**
         * 自动任务表（v24）。
         *
         * 独立于 conversations：任务的生命周期比单个会话长得多
         * （"帮我盯 CI"可能跨十几个会话），挂在会话上会跟着会话一起被删。
         * condition_mask 用位掩码存三个条件，便于后续加新条件而不改表结构。
         */
        internal val MIGRATION_23_24 = Migration(23, 24) { database ->
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS agent_tasks (" +
                    "id TEXT NOT NULL PRIMARY KEY, " +
                    "title TEXT NOT NULL, " +
                    "prompt TEXT NOT NULL, " +
                    "condition_mask INTEGER NOT NULL, " +
                    "max_rounds INTEGER NOT NULL, " +
                    "enabled INTEGER NOT NULL, " +
                    "read_only INTEGER NOT NULL, " +
                    "last_run_at INTEGER, " +
                    "last_result TEXT, " +
                    "created_at INTEGER NOT NULL)",
            )
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS index_agent_tasks_enabled_last_run " +
                    "ON agent_tasks (enabled, last_run_at)",
            )
        }

        /** 自动任务投递上限（v28）：防止重复任务在模型忘记删除时无限烧 token。 */
        internal val MIGRATION_27_28 = Migration(27, 28) { database ->
            database.execSQL("ALTER TABLE agent_tasks ADD COLUMN max_runs INTEGER NOT NULL DEFAULT 6")
            database.execSQL("ALTER TABLE agent_tasks ADD COLUMN run_count INTEGER NOT NULL DEFAULT 0")
        }

        /**
         * 计划模式（v29）：会话级阶段、版本、批准版本与计划正文。
         *
         * 与 conversation_todos 分开：todos 是执行清单，本表是"计划这个交互姿态"的状态；
         * 两者生命周期不同（计划可以被取消但正文仍要能回看）。
         */
        internal val MIGRATION_28_29 = Migration(28, 29) { database ->
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS conversation_plans (" +
                    "conversation_id TEXT NOT NULL PRIMARY KEY, " +
                    "plan_id TEXT NOT NULL, " +
                    "phase TEXT NOT NULL, " +
                    "version INTEGER NOT NULL, " +
                    "approved_version INTEGER, " +
                    "markdown TEXT NOT NULL, " +
                    "created_at INTEGER NOT NULL, " +
                    "updated_at INTEGER NOT NULL)",
            )
        }

        /** 自动任务时间语义（v27）：此前 schedule 只在域模型里，Entity 缺列导致时间语义全失效。 */
        internal val MIGRATION_26_27 = Migration(26, 27) { database ->
            database.execSQL("ALTER TABLE agent_tasks ADD COLUMN schedule_spec TEXT NOT NULL DEFAULT ''")
        }

        /** 自动任务活跃状态（v26）：一次性任务投递后置 false，区分「已执行」与「等待中」。 */
        internal val MIGRATION_25_26 = Migration(25, 26) { database ->
            database.execSQL("ALTER TABLE agent_tasks ADD COLUMN active INTEGER NOT NULL DEFAULT 1")
        }

        /** 自动任务绑定会话（v25）：执行时在原会话追加一轮，而不是新建会话。 */
        internal val MIGRATION_24_25 = Migration(24, 25) { database ->
            database.execSQL("ALTER TABLE agent_tasks ADD COLUMN conversation_id TEXT")
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


/**
 * 完整迁移链（生产与测试共用同一份）。
 *
 * 定义在文件末尾而不是 companion 里：`companion object` 的初始化早于文件级 val，
 * 在里面直接引用 MIGRATION_x 会触发 "must be initialized"。
 *
 * 以前测试里手抄了一份列表，新增迁移时两边可以不一致而测试全绿；现在唯一来源在这里 ——
 * 加了 `SCHEMA_VERSION` 却漏加迁移，`EtaDatabaseMigrationTest` 的「迁移链连续」用例会立刻变红，
 * 而不是等线上 `fallbackToDestructiveMigration` 把用户数据清空。
 */
internal val DATABASE_MIGRATIONS: Array<Migration> = arrayOf(
    EtaDatabase.MIGRATION_6_7,
    EtaDatabase.MIGRATION_7_8,
    EtaDatabase.MIGRATION_8_9,
    EtaDatabase.MIGRATION_9_10,
    EtaDatabase.MIGRATION_10_11,
    EtaDatabase.MIGRATION_11_12,
    EtaDatabase.MIGRATION_12_13,
    EtaDatabase.MIGRATION_13_14,
    EtaDatabase.MIGRATION_14_15,
    EtaDatabase.MIGRATION_15_16,
    EtaDatabase.MIGRATION_16_17,
    EtaDatabase.MIGRATION_17_18,
    EtaDatabase.MIGRATION_18_19,
    EtaDatabase.MIGRATION_19_20,
    EtaDatabase.MIGRATION_20_21,
    EtaDatabase.MIGRATION_21_22,
    EtaDatabase.MIGRATION_22_23,
    EtaDatabase.MIGRATION_23_24,
    EtaDatabase.MIGRATION_24_25,
    EtaDatabase.MIGRATION_25_26,
    EtaDatabase.MIGRATION_26_27,
    EtaDatabase.MIGRATION_27_28,
    EtaDatabase.MIGRATION_28_29,
)
