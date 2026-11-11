package io.github.mangi.eta.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 覆盖安装路径：旧 fork 线（4.0.x–4.2.0，schema 24–31）的库，能不能被本版直接打开。
 *
 * 这件事只靠「读代码觉得结构一样」不够 —— Room 在迁移后会调用 `onValidateSchema`
 * 逐表比对（列、类型、可空性、索引、外键），任何一处不同都会抛
 * `Migration didn't properly handle`，而用户看到的就是「更新后打不开」。
 * 所以这里造一个真实形态的旧库，走一遍迁移，再断言数据仍在。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class EtaDatabaseForkMigrationTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        EtaDatabase.closeForTests()
        context.deleteDatabase(DATABASE_NAME)
    }

    @After
    fun tearDown() {
        EtaDatabase.closeForTests()
        context.deleteDatabase(DATABASE_NAME)
    }

    /**
     * 4.2.0 的库（user_version = 31）→ 本版（32）：迁移后数据必须还在。
     *
     * 造库方式：先用本版建库（结构与 4.2.0 逐字一致，已逐表比对），写入数据，
     * 再把 user_version 改回 31 —— 等价于「用户手上那个 4.2.0 的库」。
     */
    @Test
    fun opensForkVersion31DatabaseAndKeepsData() = runBlocking(Dispatchers.IO) {
        EtaDatabase.get(context).openHelper.writableDatabase.execSQL(
            // history_json / applied_runtime_run_ids_json 是 NOT NULL 但没有 SQL 默认值
            //（Kotlin 侧默认值不会生成 DEFAULT），必须显式填。
            "INSERT INTO conversations " +
                "(id, title, thinking_enabled, history_json, applied_runtime_run_ids_json, created_at, updated_at) " +
                "VALUES ('conv-1', '旧会话', 0, '[]', '[]', 100, 200)",
        )
        EtaDatabase.closeForTests()

        // 伪装成 4.2.0 写的库：SQLite 的 user_version 就是 Room 认的 schema 版本
        setUserVersion(31)

        val reopened = EtaDatabase.get(context)
        val cursor = reopened.openHelper.readableDatabase.query(
            "SELECT title, created_at FROM conversations WHERE id = 'conv-1'",
        )
        cursor.use {
            assertTrue("迁移后会话丢失", it.moveToFirst())
            assertEquals("旧会话", it.getString(0))
            assertEquals(100L, it.getLong(1))
        }
        // 版本已推进到当前 schema
        assertEquals(
            EtaDatabase.SCHEMA_VERSION,
            reopened.openHelper.readableDatabase.version,
        )
    }

    /**
     * 上游血统（23）→ 本版：不应要求任何结构改动。
     *
     * 这条路径覆盖「刚装 4.3.0 的用户升级到后续版本」以及仍停留在上游 3.4.0 的设备。
     */
    @Test
    fun opensUpstreamVersion23Database() = runBlocking(Dispatchers.IO) {
        EtaDatabase.get(context).openHelper.writableDatabase.execSQL(
            "INSERT INTO conversations " +
                "(id, title, thinking_enabled, history_json, applied_runtime_run_ids_json, created_at, updated_at) " +
                "VALUES ('conv-2', '上游会话', 1, '[]', '[]', 300, 400)",
        )
        EtaDatabase.closeForTests()
        setUserVersion(23)

        val reopened = EtaDatabase.get(context)
        val cursor = reopened.openHelper.readableDatabase.query(
            "SELECT title FROM conversations WHERE id = 'conv-2'",
        )
        cursor.use {
            assertTrue("迁移后会话丢失", it.moveToFirst())
            assertEquals("上游会话", it.getString(0))
        }
    }

    /**
     * fork 中间版本（24–30）的库：带 fork 独有三表、且可能没有 auth_mode 列。
     *
     * 这里只验证迁移体对「三张残留表」的处理是幂等的——真正的结构差异
     * 已在 4.2.0 的迁移链里收敛，额外表若不清掉，Room 的结构校验会失败。
     */
    @Test
    fun dropsLegacyForkTablesWhenMigratingFromOldVersion() = runBlocking(Dispatchers.IO) {
        val db = EtaDatabase.get(context).openHelper.writableDatabase
        // 模拟 fork 留下的三张表
        db.execSQL("CREATE TABLE IF NOT EXISTS conversation_todos (id TEXT NOT NULL PRIMARY KEY)")
        db.execSQL("CREATE TABLE IF NOT EXISTS agent_tasks (id TEXT NOT NULL PRIMARY KEY)")
        db.execSQL("CREATE TABLE IF NOT EXISTS conversation_plans (id TEXT NOT NULL PRIMARY KEY)")
        EtaDatabase.closeForTests()
        setUserVersion(28)

        val reopened = EtaDatabase.get(context)
        // 迁移体内会 DROP；能正常打开本身就说明结构校验通过
        val remaining = reopened.openHelper.readableDatabase.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name IN " +
                "('conversation_todos', 'agent_tasks', 'conversation_plans')",
        )
        remaining.use { assertEquals("fork 遗留表未被清理", 0, it.count) }
    }

    /**
     * 守卫：每个已发布过的 schema 版本都必须有迁移入口。
     *
     * 漏掉任一个，Room 在覆盖安装时会走 `fallbackToDestructiveMigration` **静默清库**——
     * 这在开发期完全看不出来（新装用户的库版本永远是最新的），只有老用户升级时才炸。
     * 旧 fork 线发布过 24–31，所以这七个号都不能缺。
     */
    @Test
    fun everyReleasedSchemaVersionHasAMigrationEntry() {
        val starts = EtaDatabase.MIGRATIONS.map { it.startVersion }.toSet()
        (6 until EtaDatabase.SCHEMA_VERSION).forEach { version ->
            assertTrue("版本 $version 没有迁移入口，覆盖安装会被静默清库", version in starts)
        }
    }

    private fun setUserVersion(version: Int) {
        val path = context.getDatabasePath(DATABASE_NAME).absolutePath
        SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READWRITE).use { database ->
            database.version = version
        }
    }

    private companion object {
        const val DATABASE_NAME = "eta.db"
    }
}
