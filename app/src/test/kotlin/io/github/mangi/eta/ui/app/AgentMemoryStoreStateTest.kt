package io.github.mangi.eta.ui.app

import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.repository.AgentMemoryRepository
import io.github.mangi.eta.data.repository.ShortMemoryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 记忆页状态机（4.0 升级）：
 * - U2：击键防抖精确重算——改回原样自动清脏，保存栏消失；字节数不再是 stale 值。
 * - S2：保存走 revision 乐观锁——UI 编辑期间 Agent 的写入不被静默覆盖，冲突时载入最新。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentMemoryStoreStateTest {
    @Before
    fun setUp() {
        val app = RuntimeEnvironment.getApplication()
        io.github.mangi.eta.testutil.PlatformAssumptions.requireRenameOverwrite()
        SettingsDataStore.init(app)
        AgentMemoryRepository.rebindForTests(app)
        ShortMemoryRepository.rebindForTests(app)
        // 独立调度器 + 零防抖：状态变更与断言在同一调用栈内完成（DataStore 读取仍可能异步，用轮询兜底）。
        store = AgentMemoryStore(
            context = app,
            scope = CoroutineScope(Dispatchers.Unconfined),
            ioDispatcher = Dispatchers.Unconfined,
            mainDispatcher = Dispatchers.Unconfined,
            draftRecountDebounceMs = 0,
        )
    }

    private lateinit var store: AgentMemoryStore

    private fun awaitState(timeoutMs: Long = 3_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "等待记忆页状态超时" }
            Thread.sleep(5)
        }
    }

    @Test
    fun revertingDraftToSavedContentClearsDirty() {
        AgentMemoryRepository.replaceAll("")
        store.refreshMemory()
        awaitState { !store.memoryState.isLoading }

        store.updateMemoryDraft("临时编辑")
        assertTrue(store.memoryState.dirty)

        // 改回原样：防抖重算后自动清脏（原先无条件 dirty=true，保存栏残留下不消失）。
        store.updateMemoryDraft(store.memoryState.savedContent)
        assertFalse(store.memoryState.dirty)
    }

    @Test
    fun draftBytesAreRecountedExactlyAfterDebounce() {
        AgentMemoryRepository.replaceAll("")
        store.refreshMemory()
        awaitState { !store.memoryState.isLoading }

        store.updateMemoryDraft("中文内容")
        // 防抖任务（0ms）已把精确字节数算好：UTF-8 下 4 个汉字 = 12 字节。
        assertEquals(12, store.memoryState.draftBytes)
    }

    @Test
    fun saveDetectsRevisionConflictAndLoadsLatestWithoutOverwriting() {
        AgentMemoryRepository.replaceAll("# 核心记忆\n初始")
        store.refreshMemory()
        awaitState { !store.memoryState.isLoading && store.memoryState.savedContent.contains("初始") }

        store.updateMemoryDraft("我的编辑")
        awaitState { store.memoryState.dirty }

        // UI 编辑期间 Agent 并发写入（工具层走自己的乐观锁，拿到新 revision）。
        AgentMemoryRepository.replaceAll("# 核心记忆\nAgent 写的内容")

        store.saveMemory()
        // 没有静默覆盖 Agent 的写入；提示冲突并载入最新内容（刷新是异步的，等状态就位）。
        awaitState {
            !store.memoryState.isSaving &&
                store.memoryState.savedContent.contains("Agent 写的内容")
        }
        assertFalse(store.memoryState.savedContent.contains("我的编辑"))
        assertTrue(store.memoryState.notice != null)
    }

    @Test
    fun saveWithMatchingRevisionSucceedsAndRefreshesBaseline() {
        AgentMemoryRepository.replaceAll("# 核心记忆\n基线")
        store.refreshMemory()
        awaitState { !store.memoryState.isLoading }

        store.updateMemoryDraft("# 核心记忆\n我的保存")
        awaitState { store.memoryState.dirty }
        store.saveMemory()
        awaitState {
            !store.memoryState.isSaving && store.memoryState.savedContent.contains("我的保存")
        }
        assertFalse(store.memoryState.dirty)
        assertEquals(store.memoryState.savedContent, store.memoryState.draft)

        // 保存后基准 revision 已更新：紧接着再保存新编辑不会误报冲突。
        store.updateMemoryDraft("# 核心记忆\n再次编辑")
        awaitState { store.memoryState.dirty }
        store.saveMemory()
        awaitState { !store.memoryState.isSaving && store.memoryState.savedContent.contains("再次编辑") }
    }

    @Test
    fun rollupBusyStateIsSeparateFromSaving() {
        AgentMemoryRepository.replaceAll("")
        store.refreshMemory()
        awaitState { !store.memoryState.isLoading }

        // 无模型配置时 propose 立即失败返回消息，但 isRollingUp 不应与 isSaving 混用
        //（保存栏在整理期间误显「保存中」是 U3 要修的）。
        val seenIndependent = AtomicBoolean(false)
        store.proposeRollup()
        awaitState { !store.memoryState.isRollingUp }
        seenIndependent.set(store.memoryState.isSaving == false)
        assertTrue(seenIndependent.get())
    }
}
