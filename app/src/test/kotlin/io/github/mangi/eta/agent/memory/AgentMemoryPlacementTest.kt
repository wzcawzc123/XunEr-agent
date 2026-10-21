package io.github.mangi.eta.agent.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AgentMemoryPlacement] 的分类建议测试。
 *
 * 这些关键词直接决定"新内容会不会挤占 32K 核心预算"，写错了代价是
 * 真正每轮要用的规则被挤出核心。因此逐条钉死，包括优先级与边界。
 */
class AgentMemoryPlacementTest {

    @Test
    fun `用户指令类建议放核心`() {
        val s = AgentMemoryPlacement.suggest("- 版本号规范（用户指令，2026-10-01 起长期执行）")
        assertEquals(AgentMemoryPlacement.CORE, s.placement)
        assertTrue(s.reason.contains("用户指令") || s.reason.contains("长期执行"))
    }

    @Test
    fun `铁律建议放核心`() {
        val s = AgentMemoryPlacement.suggest("软重启与 reboot 一律由用户执行，这是铁律")
        assertEquals(AgentMemoryPlacement.CORE, s.placement)
    }

    @Test
    fun `结项类建议归档`() {
        val s = AgentMemoryPlacement.suggest("阶段一·F5 游戏插帧实测（2026-09-23 结项：全链打通）")
        assertEquals(AgentMemoryPlacement.ARCHIVE, s.placement)
    }

    @Test
    fun `收官与已交付也归档`() {
        assertEquals(
            AgentMemoryPlacement.ARCHIVE,
            AgentMemoryPlacement.suggest("PUIAOSPPackageInstall 模块折腾（收官）").placement,
        )
        assertEquals(
            AgentMemoryPlacement.ARCHIVE,
            AgentMemoryPlacement.suggest("显示增强 v6.1.2 已交付").placement,
        )
    }

    @Test
    fun `普通记录留在正文`() {
        val s = AgentMemoryPlacement.suggest("今天试了三种方式，最后用 qemu 跑通了")
        assertEquals(AgentMemoryPlacement.BODY, s.placement)
    }

    @Test
    fun `归档优先于核心`() {
        // 既说"规范"又说"结项"：它更是历史记录，不该占核心预算。
        val s = AgentMemoryPlacement.suggest("模块规范 v2 修订，已结项")
        assertEquals(AgentMemoryPlacement.ARCHIVE, s.placement)
        assertTrue("理由应说明命中结项", s.reason.contains("结项"))
    }

    @Test
    fun `空内容不猜测`() {
        val s = AgentMemoryPlacement.suggest("   ")
        assertEquals(AgentMemoryPlacement.BODY, s.placement)
        assertTrue(s.reason.contains("为空"))
    }

    @Test
    fun `每种位置都有稳定 wire 值`() {
        assertEquals("core", AgentMemoryPlacement.CORE.wire)
        assertEquals("archive", AgentMemoryPlacement.ARCHIVE.wire)
        assertEquals("body", AgentMemoryPlacement.BODY.wire)
    }
}
