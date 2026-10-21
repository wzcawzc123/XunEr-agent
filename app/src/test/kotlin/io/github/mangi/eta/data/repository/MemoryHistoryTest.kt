package io.github.mangi.eta.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 写前历史快照（S3）：每次成功落盘前把上一版写进 history/，上限 10 份 FIFO；
 * 恢复历史时当前内容先入历史（回滚可回滚）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MemoryHistoryTest {
    @Before
    fun requireRenameOverwriteSemantics() {
        io.github.mangi.eta.testutil.PlatformAssumptions.requireRenameOverwrite()
    }

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var root: java.io.File

    private fun store(): AgentMemoryStore {
        root = temporaryFolder.newFolder()
        return AgentMemoryStore(root)
    }

    /** 直接读 history/ 下的快照文件内容，避免 restoreHistory 的副作用污染断言。 */
    private fun historyContent(id: String): String =
        java.io.File(java.io.File(java.io.File(root, "memory"), "history"), id).readText(Charsets.UTF_8)

    @Test
    fun writesLeaveHistorySnapshotsOfThePreviousVersion() {
        val store = store()
        store.replaceAll("# 核心记忆\n第一版")
        store.replaceAll("# 核心记忆\n第二版")
        store.replaceAll("# 核心记忆\n第三版")

        val history = store.listHistory()
        // 两次换代各留一版：第三版落盘时留下第二版、第二版落盘时留下第一版（最新在前）。
        assertEquals(2, history.size)
        assertTrue(historyContent(history[0].id).contains("第二版"))
        assertTrue(historyContent(history[1].id).contains("第一版"))
    }

    @Test
    fun clearIsRecoverableFromHistory() {
        val store = store()
        store.replaceAll("# 核心记忆\n重要内容")
        store.replaceAll("")

        val history = store.listHistory()
        assertEquals(1, history.size)
        val restored = store.restoreHistory(history.single().id)
        assertEquals("# 核心记忆\n重要内容", restored.content)
    }

    @Test
    fun restoreSnapshotsCurrentContentFirstSoRollbackIsReversible() {
        val store = store()
        store.replaceAll("A 版本")
        store.replaceAll("B 版本")
        val aSnapshot = store.listHistory().single { historyContent(it.id) == "A 版本" }
        store.restoreHistory(aSnapshot.id)
        // 恢复 A 前自动留了 B 版本的档：回滚也能再回滚。
        val ids = store.listHistory().map { historyContent(it.id) }
        assertTrue(ids.contains("B 版本"))
    }

    @Test
    fun historyIsCappedAtTenEntriesOldestDropped() {
        val store = store()
        repeat(13) { iteration -> store.replaceAll("版本 $iteration") }
        val history = store.listHistory()
        assertEquals(AgentMemoryStore.MAX_HISTORY_ENTRIES, history.size)
        // 每次写入留下上一版：写「版本 N」留「版本 N-1」。13 次写入共 12 份快照，
        // 保留最新 10 份 = 版本 2..11，最老的「版本 0」「版本 1」被挤掉。
        val contents = history.map { historyContent(it.id) }
        assertTrue(contents.first().contains("版本 11"))
        assertFalse(contents.any { it.contains("版本 0") })
    }

    @Test
    fun emptyPreviousContentIsNotArchived() {
        val store = store()
        store.replaceAll("首个内容")
        // 初始为空时不产生历史；只有非空内容被覆盖才留档。
        assertEquals(0, store.listHistory().size)
    }

    @Test
    fun restoreRejectsPathTraversalIds() {
        val store = store()
        assertThrows(AgentMemoryException::class.java) {
            store.restoreHistory("../../evil.md")
        }
        assertThrows(AgentMemoryException::class.java) {
            store.restoreHistory("not-a-snapshot.md")
        }
    }

    @Test
    fun failedOverSizeWriteStillKeepsHistoryAndFile() {
        val store = store()
        val original = store.replaceAll("原内容")
        assertThrows(AgentMemoryException::class.java) {
            store.replaceAll("a".repeat(AgentMemoryStore.MAX_FILE_BYTES + 1))
        }
        assertEquals(original.content, store.snapshot().content)
        assertFalse(store.listHistory().isEmpty())
    }
}
