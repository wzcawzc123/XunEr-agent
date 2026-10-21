package io.github.mangi.eta.data.repository

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 防 A 复发（4.0 重建曾漏 init 短期仓库，短期 tab / target=short 工具 / 整理整条线不可用）：
 * [MemoryRepositories.init] 必须同时就位长期与短期两个仓库。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MemoryRepositoriesInitTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        io.github.mangi.eta.testutil.PlatformAssumptions.requireRenameOverwrite()
        // 与 EtaApp.onCreate 同序：SettingsDataStore 先于记忆仓库就绪（warmEnabledCache 要读它）。
        io.github.mangi.eta.data.datastore.SettingsDataStore.init(context)
    }

    @Test
    fun initBringsUpBothLongAndShortRepositories() {
        MemoryRepositories.init(context)

        // 长期可用。
        val longSnapshot = AgentMemoryRepository.replaceAll("# 核心记忆\n长期内容")
        assertTrue(longSnapshot.content.contains("长期内容"))

        // 短期可用——4.0 重建回归时这里会抛 IllegalStateException。
        val shortSnapshot = ShortMemoryRepository.replaceAll("# 任务\n短期内容")
        assertTrue(shortSnapshot.content.contains("短期内容"))
        assertTrue(ShortMemoryRepository.snapshot().content.contains("短期内容"))

        // 两个仓库物理隔离：互不污染。
        assertTrue(AgentMemoryRepository.snapshot().content.contains("长期内容"))
        assertEquals(
            false,
            AgentMemoryRepository.snapshot().content.contains("短期内容"),
        )
    }
}
