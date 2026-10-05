package io.github.mangi.eta.agent.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M1.4 表驱动锁（2026-10-04 用户决策）：默认敏感、当轮可见、持久形状化；
 * 记忆与读图不设默认豁免；跨会话靠记忆系统与再取数，不靠 transcript。
 * 策略真源 = [AgentSensitiveToolPolicy]，权限清单由其派生，禁止再复制名单。
 */
class AgentSensitiveToolPolicyTest {

    @Test
    fun memoryAndImageHaveNoDefaultExemption() {
        listOf(
            "memory_get", "memory_write", "character_memory_get", "character_memory_write",
            "read_image",
        ).forEach { name ->
            assertTrue("$name 必须按默认敏感处理", AgentSensitiveToolPolicy.isSensitive(name))
        }
    }

    @Test
    fun deviceActionGroupIsCompleteIncludingPreviouslyMissingTwo() {
        // 散落期欠账：set_device_state/app_state_control 曾漏挂策略表（只有 set_setting）。
        listOf("set_setting", "set_device_state", "app_state_control").forEach { name ->
            assertTrue("$name 参数须形状化持久", AgentSensitiveToolPolicy.isSensitive(name))
        }
        assertTrue(AgentSensitiveToolPolicy.SENSITIVE_DEVICE_ACTION.containsAll(
            listOf("set_setting", "set_device_state", "app_state_control"),
        ))
    }

    @Test
    fun mcpPrefixIsAlwaysSensitive() {
        assertTrue(AgentSensitiveToolPolicy.isSensitive("mcp_server_search_deadbeef"))
        assertTrue(AgentSensitiveToolPolicy.isSensitive("mcp_"))
    }

    @Test
    fun ordinaryGuiAndTerminalToolsStayNonSensitive() {
        // 非敏感面不扩大化：观察/手势/终端/文件工具保持可见（终端有自己的权限门）。
        listOf(
            "observe_screen", "locate_on_screen", "tap", "tap_element", "scroll",
            "terminal", "run_command", "read_file", "launch_app",
        ).forEach { name ->
            assertFalse("$name 不应被策略表误伤", AgentSensitiveToolPolicy.isSensitive(name))
        }
    }

    @Test
    fun groupsAreDisjointAndExactlyCoverTheTable() {
        val read = AgentSensitiveToolPolicy.SENSITIVE_DEVICE_READ
        val action = AgentSensitiveToolPolicy.SENSITIVE_DEVICE_ACTION
        val memory = AgentSensitiveToolPolicy.MEMORY_TOOLS
        val image = AgentSensitiveToolPolicy.IMAGE_TOOLS
        // v3.8.0 合并上游 3.2.0：个人上下文检索与系统应用操作（读/写）入表，
        // 计数锁随名单合法扩容更新（31→46 / 3→15）；互斥与覆盖断言保持不变。
        assertEquals(46, read.size)
        assertEquals(15, action.size)
        assertEquals(4, memory.size)
        assertEquals(setOf("read_image"), image)
        // 分组互斥：并集大小 = 各组之和（防止一个工具被重复归类后行为漂移）
        assertEquals(read.size + action.size + memory.size + image.size,
            (read + action + memory + image).size)
        assertTrue(read.contains("get_logcat"))
        assertFalse(read.contains("set_setting"))
    }
}
