package io.github.mangi.eta.agent.plan

/**
 * `exit_plan_mode` 回执的结构化封装。
 *
 * 为什么需要它：计划正文是 Markdown，但工具回执 historically 是**纯文本**，
 * UI 只能整段塞进一个 `Text` 里 —— 结果标题/列表/代码块的 Markdown 语法原样显示，
 * 还带 `maxLines` 截断（真机反馈"展开也看不全"）。
 *
 * 做法：回执用固定标记把正文包起来，UI 拆成「回执头 + 计划正文」两块，
 * 正文交给与聊天气泡同款的 Markdown 渲染。
 *
 * 用固定标记而不是 `---` 分隔线：模型完全可能在计划正文里写水平分隔线，
 * 那种分隔方式会把正文从中间劈开。
 */
internal object AgentPlanReceipt {

    /** 计划正文起始标记。 */
    const val BEGIN = "[ETA_PLAN_BEGIN]"

    /** 计划正文结束标记。 */
    const val END = "[ETA_PLAN_END]"

    /** 拆解结果：回执头（版本/步数/等待批准等说明）与计划正文（Markdown）。 */
    data class Parsed(val header: String, val markdown: String)

    /** 拼装回执：头 + 标记包裹的正文。 */
    fun build(header: String, markdown: String): String = buildString {
        append(header)
        append('\n')
        append(BEGIN)
        append('\n')
        append(markdown)
        append('\n')
        append(END)
    }

    /**
     * 解析回执；不是计划回执（无标记/标记不成对）时返回 null。
     *
     * 容错：标记出现多次时取第一对；正文为空时返回空串而不是 null ——
     * 「有计划但正文为空」与「这不是计划回执」是两回事，前者仍应走 Markdown 分支。
     */
    fun parse(summary: String?): Parsed? {
        val text = summary?.takeIf { it.isNotBlank() } ?: return null
        val begin = text.indexOf(BEGIN)
        val end = text.indexOf(END, begin + BEGIN.length)
        if (begin < 0 || end < 0) return null
        val header = text.substring(0, begin).trim()
        val markdown = text.substring(begin + BEGIN.length, end).trim()
        return Parsed(header = header, markdown = markdown)
    }
}
