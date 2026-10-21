package io.github.mangi.eta.data.repository

import android.content.Context
import kotlinx.coroutines.runBlocking

/**
 * 记忆仓库统一初始化入口：长期 + 短期一次就位。
 *
 * 4.0 重建时只 init 了长期仓库，短期整条线（短期 tab、`target="short"` 工具、整理）
 * 因此不可用，而单测不覆盖 Application 初始化，CI 也不报。收敛为单入口后，
 * 「漏 init 某个仓库」在结构上不再可能。
 */
internal object MemoryRepositories {
    fun init(context: Context) {
        AgentMemoryRepository.init(context)
        ShortMemoryRepository.init(context)
        // 启动读一次开关进缓存（Application.onCreate 同步路径，与 EtaApp 既有
        // runBlocking 读设置一致）；此后工具派发路径直接读缓存。
        runBlocking { AgentMemoryRepository.warmEnabledCache() }
    }
}
