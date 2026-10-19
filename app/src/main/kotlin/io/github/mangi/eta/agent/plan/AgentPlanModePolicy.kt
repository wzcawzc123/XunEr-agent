package io.github.mangi.eta.agent.plan

import io.github.mangi.eta.agent.context.PersonalSearchTools
import io.github.mangi.eta.agent.model.AgentConversationToolCatalog
import io.github.mangi.eta.agent.model.AgentPhoneToolCatalog
import org.json.JSONObject

/**
 * 计划模式的运行时只读约束。
 *
 * 为什么不能只靠提示词：Codex 的 Plan 模式也承认约束只在 prompt 层，模型偶尔会"顺手"改东西；
 * 本项目的设备工具一旦执行就是真实副作用（点按、发消息、写文件），必须有一层硬拦截。
 *
 * 采用**白名单**而不是黑名单：计划阶段允许的工具必须被显式列出；新增的写工具默认被拦截，
 * 漏配的代价是"计划阶段不能用"，而不是"计划阶段把设备改了"。
 */
internal object AgentPlanModePolicy {

    /** 纯读取/观察类工具；计划阶段可用的完整集合。 */
    private val READ_ONLY: Set<String> = buildSet {
        addAll(
            listOf(
                // 环境与设备状态
                "get_current_context", "device_status", "get_device_environment", "get_current_location",
                "network_info", "get_setting", "get_clipboard", "inspect_app",
                // 屏幕观察（scroll 只移动视口，不改数据，允许用于翻看长页面）
                "observe_screen", "locate_on_screen", "wait", "wait_for_text", "wait_for_package",
                "scroll", "scroll_element",
                // 文件读取
                "read_file", "list_directory", "stat_file", "glob_files", "grep_files", "read_image",
                // 网络读取
                "web_search", "fetch_url",
                // 记忆与技能读取
                "memory_get", "character_memory_get",
                "skills_list", "skills_read", "skills_read_resource", "skills_list_curated",
                "skills_inspect_github",
                // 历史与检索
                AgentConversationToolCatalog.READ_HISTORY,
                "search_apps", "list_auto_tasks", "androguard_analyze",
                // 个人信息只读检索
                "search_notification_history", "recent_notifications", "recent_app_activity",
                "app_usage_summary", "search_personal_orders", "top_memory_apps", "top_storage_apps",
                "list_alarms", "list_active_timers", "get_health_summary", "search_clipboard_history",
                "search_media", "search_audio", "search_recordings", "search_files",
                "search_calendar_events", "search_contacts", "search_call_history", "search_messages",
                "search_downloads", "search_notes", "search_coloros_recordings",
                "search_recording_summaries", "search_system_memories", "search_saved_places",
                "search_qq_chat_images", "search_wechat_chat_images",
            ),
        )
        addAll(PersonalSearchTools.names)
        // 电话/日历/短信等目录里 write=false 的查询工具自动纳入；write=true 的一律不列。
        AgentPhoneToolCatalog.entries.filterNot { it.write }.forEach { add(it.name) }
    }

    /** browser_use 是复合工具：只放行读取类 action，点击/输入/提交一律拦截。 */
    private val READ_ONLY_BROWSER_ACTIONS = setOf(
        "navigate", "get_readable", "find_elements", "screenshot", "get_text", "read", "wait",
    )

    fun isBlocked(phase: AgentPlanPhase, toolName: String, args: JSONObject? = null): Boolean {
        if (!phase.isReadOnly) return false
        // 计划阶段唯一的写操作：提交计划本身。
        if (phase == AgentPlanPhase.PLANNING && toolName == AgentPlanModeTool.EXIT_NAME) return false
        // 重复进入计划模式是幂等的提示，不拦截；REVIEW 阶段则不再允许重新进入。
        if (phase == AgentPlanPhase.PLANNING && toolName == AgentPlanModeTool.ENTER_NAME) return false
        if (toolName == "browser_use") {
            val action = args?.optString("action")?.trim()?.lowercase().orEmpty()
            return action !in READ_ONLY_BROWSER_ACTIONS
        }
        return toolName !in READ_ONLY
    }

    fun rejection(phase: AgentPlanPhase, toolName: String): String = when (phase) {
        AgentPlanPhase.PLANNING ->
            "失败：当前处于计划模式（计划中），只允许只读调研；$toolName 已被拦截。" +
                "请先完成调研并用 ${AgentPlanModeTool.EXIT_NAME} 提交计划。"
        AgentPlanPhase.REVIEW ->
            "失败：计划已提交，正在等待用户批准；批准前不能执行 $toolName。" +
                "请等待用户回复「批准」或「取消」。"
        else -> "失败：计划模式只读约束异常（$toolName）。"
    }
}
