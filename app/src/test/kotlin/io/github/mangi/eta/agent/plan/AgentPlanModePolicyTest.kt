package io.github.mangi.eta.agent.plan

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPlanModePolicyTest {

    @Test
    fun `普通对话不拦截任何工具`() {
        assertFalse(AgentPlanModePolicy.isBlocked(AgentPlanPhase.NORMAL, "write_file"))
        assertFalse(AgentPlanModePolicy.isBlocked(AgentPlanPhase.NORMAL, "tap"))
        assertFalse(AgentPlanModePolicy.isBlocked(AgentPlanPhase.EXECUTING, "tap"))
    }

    @Test
    fun `计划中允许只读调研工具`() {
        listOf(
            "read_file", "list_directory", "grep_files", "observe_screen", "locate_on_screen",
            "web_search", "fetch_url", "memory_get", "skills_read", "scroll",
            AgentPlanModeTool.ENTER_NAME, AgentPlanModeTool.EXIT_NAME,
        ).forEach { name ->
            assertFalse("$name 应该允许", AgentPlanModePolicy.isBlocked(AgentPlanPhase.PLANNING, name))
        }
    }

    @Test
    fun `计划中拦截修改类工具`() {
        listOf(
            "write_file", "edit_file", "terminal", "run_command", "todo_write",
            "memory_write", "character_memory_write", "create_auto_task", "delete_auto_task",
            "tap", "tap_element", "type_text", "swipe", "launch_app", "open_uri",
            "set_device_state", "app_state_control", "set_flashlight",
        ).forEach { name ->
            assertTrue("$name 应该被拦截", AgentPlanModePolicy.isBlocked(AgentPlanPhase.PLANNING, name))
        }
    }

    @Test
    fun `待批准阶段继续拦截修改且不允许重新进入`() {
        assertTrue(AgentPlanModePolicy.isBlocked(AgentPlanPhase.REVIEW, "tap"))
        assertTrue(AgentPlanModePolicy.isBlocked(AgentPlanPhase.REVIEW, "todo_write"))
        assertTrue(AgentPlanModePolicy.isBlocked(AgentPlanPhase.REVIEW, AgentPlanModeTool.ENTER_NAME))
        assertTrue(AgentPlanModePolicy.isBlocked(AgentPlanPhase.REVIEW, AgentPlanModeTool.EXIT_NAME))
        assertFalse(AgentPlanModePolicy.isBlocked(AgentPlanPhase.REVIEW, "read_file"))
    }

    @Test
    fun `浏览器只放行读取类 action`() {
        assertFalse(
            AgentPlanModePolicy.isBlocked(
                AgentPlanPhase.PLANNING,
                "browser_use",
                JSONObject().put("action", "get_readable"),
            ),
        )
        assertTrue(
            AgentPlanModePolicy.isBlocked(
                AgentPlanPhase.PLANNING,
                "browser_use",
                JSONObject().put("action", "click"),
            ),
        )
        assertTrue(
            AgentPlanModePolicy.isBlocked(
                AgentPlanPhase.PLANNING,
                "browser_use",
                JSONObject().put("action", "type"),
            ),
        )
    }

    @Test
    fun `未知工具默认拦截`() {
        assertTrue(AgentPlanModePolicy.isBlocked(AgentPlanPhase.PLANNING, "some_new_mutating_tool"))
    }
}
