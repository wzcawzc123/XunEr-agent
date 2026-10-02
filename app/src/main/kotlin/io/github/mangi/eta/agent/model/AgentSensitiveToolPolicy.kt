package io.github.mangi.eta.agent.model

/** 标记原始参数或结果不得进入持久会话的工具。 */
internal object AgentSensitiveToolPolicy {
    fun isSensitive(toolName: String): Boolean =
        toolName.startsWith("mcp_") || toolName in sensitiveTools

    /**
     * 结果正文允许进入持久会话的工具。
     *
     * MEMORY.md 由 agent 自己写入，且核心部分每轮启动已随 `<memory_core>` 注入，
     * 因此保留结果正文的额外隐私暴露很小；而抹掉正文会让模型在后续轮次看不到自己刚读到的内容，
     * 只能反复重读（实测同一会话出现 34 次完全相同的 memory_get、1,283 次 terminal）。
     *
     * 注意：这些工具的**参数**仍然按形状脱敏，不在此豁免范围内。
     */
    fun isResultVisible(toolName: String): Boolean = toolName in resultVisibleTools

    private val resultVisibleTools = setOf(
        "memory_get",
        "memory_write",
    )

    private val sensitiveTools = setOf(
        "get_setting",
        "wifi_credentials",
        "recent_notifications",
        "search_notification_history",
        "recent_app_activity",
        "app_usage_summary",
        "get_current_location",
        "get_device_environment",
        "list_alarms",
        "list_active_timers",
        "search_clipboard_history",
        "get_health_summary",
        "read_sms_code",
        "get_logcat",
        "search_media",
        "search_audio",
        "search_recordings",
        "search_files",
        "search_calendar_events",
        "search_contacts",
        "search_call_history",
        "search_messages",
        "search_downloads",
        "search_coloros_notes",
        "search_coloros_recordings",
        "search_recording_summaries",
        "search_coloros_memories",
        "search_saved_places",
        "search_personal_orders",
        "search_qq_chat_images",
        "search_wechat_chat_images",
        "read_image",
        "set_setting",
        "memory_get",
        "memory_write",
        "character_memory_get",
        "character_memory_write",
    )
}
