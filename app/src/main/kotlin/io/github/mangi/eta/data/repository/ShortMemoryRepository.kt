package io.github.mangi.eta.data.repository

import android.content.Context
import java.io.File

/**
 * 短期记忆（当前任务/会话的临时事实）仓库，与长期 [AgentMemoryRepository] 完全隔离。
 *
 * 设计（spec：memory-upgrade §3.1）：
 * - 独立文件 `filesDir/memory-short/memory/MEMORY.md`（与长期 `filesDir/memory/MEMORY.md` 分开），
 *   清空短期不影响长期；
 * - 复用 [AgentMemoryStore]（同一套 revision 乐观锁 / AtomicFile 原子写 / 1 MiB 上限 /
 *   3500 字符单写）——rootDir 参数化已有先例（角色记忆 `CharacterMemoryRepository` 每角色一个 store）；
 * - **不参与核心注入**：短期不进 `ConversationMemoryPins` / 不进 system prompt 正文，
 *   按需 `memory_get(target="short")` 读取——避免破坏长期注入的前缀缓存。
 */
internal object ShortMemoryRepository : MemoryRepository {
    @Volatile
    private lateinit var store: AgentMemoryStore

    fun init(context: Context) {
        if (!::store.isInitialized) {
            store = AgentMemoryStore(File(context.applicationContext.filesDir, SHORT_ROOT_DIR))
        }
    }

    override fun snapshot(): AgentMemorySnapshot {
        ensureInitialized()
        return store.snapshot()
    }

    override fun read(
        query: String?,
        startLine: Int,
        maxChars: Int,
    ): AgentMemoryReadResult {
        ensureInitialized()
        return store.read(query, startLine, maxChars)
    }

    override fun readSection(
        section: String,
        maxChars: Int,
    ): AgentMemoryReadResult {
        ensureInitialized()
        return store.readSection(section, maxChars)
    }

    override fun mutate(mutation: AgentMemoryMutation): AgentMemoryWriteResult {
        ensureInitialized()
        return store.mutate(mutation)
    }

    override fun replaceAll(content: String): AgentMemorySnapshot {
        ensureInitialized()
        return store.replaceAll(content)
    }

    override fun replaceAllIfRevision(content: String, revision: String): AgentMemoryWriteResult {
        ensureInitialized()
        return store.replaceAllIfRevision(content, revision)
    }

    override fun listHistory(): List<AgentMemoryHistoryEntry> {
        ensureInitialized()
        return store.listHistory()
    }

    override fun restoreHistory(id: String): AgentMemorySnapshot {
        ensureInitialized()
        return store.restoreHistory(id)
    }

    /** 仅供测试：与 [AgentMemoryRepository.rebindForTests] 同因（Robolectric 每类新 Application）。 */
    @androidx.annotation.VisibleForTesting
    internal fun rebindForTests(context: Context) {
        store = AgentMemoryStore(File(context.applicationContext.filesDir, SHORT_ROOT_DIR))
    }

    private fun ensureInitialized() {
        check(::store.isInitialized) {
            "ShortMemoryRepository.init(context) must be called in Application.onCreate()"
        }
    }

    private const val SHORT_ROOT_DIR = "memory-short"
}
