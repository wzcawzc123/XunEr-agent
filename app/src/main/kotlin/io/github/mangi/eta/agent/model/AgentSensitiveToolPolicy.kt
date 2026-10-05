package io.github.mangi.eta.agent.model

/**
 * 敏感工具策略**单一真源**（M1.4，2026-10-04 用户决策）。
 *
 * 决策原文：默认敏感——当轮可见、持久形状化；记忆与读图不设默认豁免；
 * 跨会话靠记忆系统与再取数，不靠 transcript。
 *
 * | 类别 | 参数持久化 | 结果持久化 | 当轮模型可见 |
 * |------|-----------|-----------|-------------|
 * | 设备信息读取（[SENSITIVE_DEVICE_READ]） | 形状脱敏 | 否 | 是 |
 * | 设备状态写入（[SENSITIVE_DEVICE_ACTION]） | 形状脱敏 | 否 | 是 |
 * | 记忆系统（[MEMORY_TOOLS]，不设豁免） | 形状脱敏 | 否 | 是 |
 * | 图片读取（[IMAGE_TOOLS]，不设豁免） | 形状脱敏 | 否 | 是 |
 * | MCP 工具（`mcp_` 前缀） | 形状脱敏 | 否 | 是 |
 *
 * 消费链：`AgentLoop` 收集 sensitiveToolCallIds → `AgentConversationCodec.transcript`
 * 形状化脱敏（`{_redacted,_note,_fields}`）；`AgentLocalTools` 出口按本表强制
 * `ToolResult.sensitive=true`。当轮 in-run 消息不受影响，跨会话占位符由
 * `REDACTED_ARGUMENTS_REPLAYED` 引导再取数。
 *
 * 分组被 `AgentLocalTools` 的权限清单引用——改这里即全链生效，禁止再复制粘贴名单。
 */
internal object AgentSensitiveToolPolicy {

    /** 设备信息读取：结果含个人数据（凭据/通知/位置/健康/文件索引等）。 */
    val SENSITIVE_DEVICE_READ: Set<String> = setOf(
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
        "search_notes",
        "search_coloros_recordings",
        "search_recording_summaries",
        "search_coloros_memories",
        "search_system_memories",
        "search_saved_places",
        "search_personal_orders",
        "search_qq_chat_images",
        "search_wechat_chat_images",
    ) + io.github.mangi.eta.agent.context.PersonalSearchTools.names + AgentPhoneToolCatalog.reads

    /**
     * 设备状态写入：参数含目标包名/系统开关。此前只有 set_setting 在表内，
     * set_device_state/app_state_control 漏挂（散落期的欠账），按“默认敏感”补齐。
     */
    val SENSITIVE_DEVICE_ACTION: Set<String> = setOf(
        "set_setting",
        "set_device_state",
        "app_state_control",
    ) + AgentPhoneToolCatalog.writes

    /** 记忆系统：用户明确不设默认豁免（旧实现已含，保留）。 */
    val MEMORY_TOOLS: Set<String> = setOf(
        "memory_get",
        "memory_write",
        "character_memory_get",
        "character_memory_write",
    )

    /**
     * 图片读取：用户明确不设默认豁免。旧实现曾豁免（“功能优先于隐私”，防占位符
     * 无法归因串图），已废除——归因靠当轮可见，跨会话靠再取数。
     */
    val IMAGE_TOOLS: Set<String> = setOf("read_image")

    private val allSensitive: Set<String> =
        SENSITIVE_DEVICE_READ + SENSITIVE_DEVICE_ACTION + MEMORY_TOOLS + IMAGE_TOOLS

    fun isSensitive(toolName: String): Boolean =
        toolName.startsWith("mcp_") || toolName in allSensitive ||
            toolName in io.github.mangi.eta.agent.context.PersonalSearchTools.names ||
            toolName in AgentPhoneToolCatalog.names
}
