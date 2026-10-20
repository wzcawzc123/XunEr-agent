package io.github.mangi.eta.agent.plan

/**
 * 模型对「要不要进入计划模式」的**建议**（文本协议）。
 *
 * 为什么是建议而不是模型自行进入：计划模式的本质是权限边界切换（只读 ↔ 可写），
 * 让模型自行切换等于让它自己改自己的权限 —— 这是权限设计上的反模式，
 * 主流产品（Claude Code / Kimi CLI / Cursor CLI / Codex / Aider…）无一例外
 * 都由用户主动开启。建议机制是两者的折中：模型判断任务复杂时提一句，
 * 用户点一下才进入，决定权始终在人。
 *
 * 协议：模型在回复**最后一行**单独输出标记，可跟一句理由：
 *
 * ```
 * 这个任务要改 3 个文件还要动系统设置，建议先出方案再动手。
 * [[PLAN_SUGGESTION]] 先调研再改，避免改错
 * ```
 *
 * UI 拆成「正文（去掉标记行）+ 建议条（带理由 + 进入按钮）」。
 */
internal object AgentPlanSuggestion {

    /** 建议标记。模型输出它即表示"我建议进入计划模式"。 */
    const val MARKER = "[[PLAN_SUGGESTION]]"

    /** 拆解结果：去掉标记后的正文，与标记后的理由（可能为空）。 */
    data class Parsed(val body: String, val reason: String?)

    /**
     * 从回复文本里拆出建议。
     *
     * - 只在**最后一行**匹配标记：模型在正文中间提到这个词不算建议，避免误触发。
     * - 没有标记返回 null（绝大多数回复走这条）。
     * - 标记存在但理由为空时 `reason = null`，UI 用默认文案。
     */
    fun parse(text: String?): Parsed? {
        val content = text?.takeIf { it.isNotBlank() } ?: return null
        val lines = content.lines()
        val markerIndex = lines.indexOfLast { it.trim().startsWith(MARKER) }
        // 必须是最后一行（允许其后只有空行）。
        if (markerIndex < 0) return null
        if (lines.drop(markerIndex + 1).any { it.isNotBlank() }) return null
        val markerLine = lines[markerIndex].trim()
        val reason = markerLine.removePrefix(MARKER).trim().takeIf { it.isNotEmpty() }
        val body = lines.take(markerIndex).joinToString("\n").trim()
        return Parsed(body = body, reason = reason)
    }

    /** 拼装系统提示里的协议说明（供 [AgentPromptBuilder] 使用）。 */
    fun promptInstruction(): String =
        "如果当前任务比较复杂（多步骤、要改多处、有不可逆操作），你可以在回复的" +
            "最后一行单独输出 $MARKER 并跟一句理由，界面会渲染一个「进入计划模式」按钮让用户选择；" +
            "不要替用户决定进入，也不要频繁建议——简单任务直接做。"

}
