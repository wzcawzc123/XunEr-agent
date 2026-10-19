package io.github.mangi.eta.agent.plan

import org.json.JSONArray
import org.json.JSONObject

/**
 * `todo_write` 工具：唯一的清单写入口，语义是**全量覆盖**。
 *
 * 为什么只做一个工具、且是全量覆盖：
 * - 单线程 agent 不存在并发改同一张表的场景，增量更新（create/update/delete 三件套）
 *   只会多出三类「状态对不上」的可能；
 * - 模型一次提交完整清单，天然自带「当前全貌」，也让提示词更容易写。
 *
 * 这个类只负责协议（解析 JSON、产出面向模型的文本），存储交给 [AgentPlanStore]，
 * 校验交给 [AgentPlanItem]。三层各自可测。
 */
internal object AgentPlanTool {

    const val NAME = "todo_write"

    /** 注入提示里用来标识清单概览的稳定前缀。 */
    const val OVERVIEW_PREFIX = "[任务清单]"

    /**
     * 执行期改动超出批准范围时，回执的固定前缀。
     *
     * 工具层用它判断"是否要把阶段退回待批准"。用一个稳定前缀而不是解析整句：
     * 文案以后可以改，判定不能跟着改。
     */
    const val SCOPE_CHANGE_PREFIX = "超出已批准范围："

    /** 完整清单注入最多显示多少条，超出只报总数（避免把摘要撑爆）。 */
    private const val MAX_CHECKLIST_LINES = 30

    /** 计划正文注入的默认预算：够放目标、约束和验收标准，又不至于 dominates 整个上下文。 */
    private const val MAX_PLAN_CONTEXT_CHARS = 1_200

    fun schema(): JSONObject = JSONObject()
        .put("name", NAME)
        .put(
            "description",
            "维护当前任务的步骤清单。**全量覆盖**：每次提交完整列表，" +
                "省略某条等于删除它；传空数组等于清空。 " +
                "仅用于需要 3 步以上、或用户明确要求列计划的复杂任务；" +
                "简单问答不要调用。 " +
                "开始一步时把它的 status 置为 in_progress（同时只能有一条），" +
                "**验证通过后**再置为 completed；做不了或被用户取消的步骤置为 cancelled，" +
                "不要用 completed 掩盖未完成。 " +
                "status 只接受 pending / in_progress / completed / cancelled，" +
                "写 done、完成等别名会被拒绝。",
        )
        .put(
            "parameters",
            JSONObject()
                .put("type", "object")
                .put(
                    "properties",
                    JSONObject().put(
                        "items",
                        JSONObject()
                            .put("type", "array")
                            .put("description", "完整清单，按执行顺序排列")
                            .put(
                                "items",
                                JSONObject()
                                    .put("type", "object")
                                    .put(
                                        "properties",
                                        JSONObject()
                                            .put(
                                                "id",
                                                JSONObject()
                                                    .put("type", "string")
                                                    .put("description", "稳定标识，省略则按位置生成 step-N"),
                                            )
                                            .put(
                                                "subject",
                                                JSONObject()
                                                    .put("type", "string")
                                                    .put("description", "步骤标题（名词形态），如「修复压缩判据」"),
                                            )
                                            .put(
                                                "activeForm",
                                                JSONObject()
                                                    .put("type", "string")
                                                    .put("description", "进行时形态，如「正在修复压缩判据」；省略则自动补「正在」前缀"),
                                            )
                                            .put(
                                                "status",
                                                JSONObject()
                                                    .put("type", "string")
                                                    .put(
                                                        "enum",
                                                        JSONArray()
                                                            .put(AgentPlanStatus.PENDING.wire)
                                                            .put(AgentPlanStatus.IN_PROGRESS.wire)
                                                            .put(AgentPlanStatus.COMPLETED.wire)
                                                            .put(AgentPlanStatus.CANCELLED.wire),
                                                    )
                                                    .put("description", "默认 pending"),
                                            )
                                            .put(
                                                "reopen",
                                                JSONObject()
                                                    .put("type", "boolean")
                                                    .put(
                                                        "description",
                                                        "仅用于把已完成/已取消的步骤重新打开；不传则视为状态回退，会被拒绝",
                                                    ),
                                            ),
                                    )
                                    .put("required", JSONArray().put("subject")),
                            ),
                    ),
                )
                .put("required", JSONArray().put("items")),
        )

    /**
     * 执行一次全量覆盖。
     *
     * @param enforceApprovedScope 处于 EXECUTING 时传 true：新增/删除/改写步骤属于
     *   **改变已批准的范围**，回执以 [SCOPE_CHANGE_PREFIX] 开头，由调用方把阶段退回待批准。
     *   只改 status 不受影响 —— 执行本来就要推进状态。
     * @return 面向模型的文本结果：成功时回显进度与当前步骤，失败时给出可直接读懂的
     *   中文原因（不抛异常 —— 抛异常会把工具错误变成运行失败）。
     */
    fun execute(
        args: JSONObject,
        store: AgentPlanStore,
        conversationId: String,
        nowMillis: Long,
        enforceApprovedScope: Boolean = false,
    ): String {
        if (conversationId.isBlank()) {
            return "失败：当前会话尚无标识，无法保存任务清单。"
        }

        val rawItems = args.optJSONArray("items")
            ?: return "失败：缺少必填参数 items（数组）。"

        val parsed = ArrayList<AgentPlanItem>(rawItems.length())
        val reopenIds = mutableSetOf<String>()
        for (index in 0 until rawItems.length()) {
            val raw = rawItems.optJSONObject(index)
                ?: return "失败：items[$index] 不是对象。"
            // 缺省 status 是合法的（默认 pending）；但**非法值不能静默降级** ——
            // 模型写 "done"/"完成" 时若被当成 pending，它会收到成功回执并继续以为该步已完成，
            // 进度却永远差一步。这里直接拒绝，并在错误里列出可用值。
            val rawStatus = raw.optString("status").trim()
            val status = if (rawStatus.isEmpty()) {
                AgentPlanStatus.PENDING
            } else {
                AgentPlanStatus.fromWire(rawStatus)
                    ?: return "失败：items[$index].status=\"$rawStatus\" 不合法；可用值：" +
                        AgentPlanStatus.entries.joinToString(" / ") { it.wire }
            }
            val item = AgentPlanItem.create(
                index = index,
                // 用单参 optString（缺省返回 ""）+ takeIf 判空，而不是 optString(key, null)：
                // Android 版 org.json 的 fallback 参数不接受 null，会抛 JSONException。
                // 这个差异在桌面 JVM 上不会复现，只有 CI 的 Robolectric 能暴露它。
                id = raw.optString("id").takeIf { it.isNotBlank() },
                subject = raw.optString("subject").takeIf { it.isNotBlank() },
                activeForm = raw.optString("activeForm").takeIf { it.isNotBlank() },
                status = status,
            ).getOrElse { return "失败：${it.message}" }
            if (raw.optBoolean("reopen", false)) reopenIds += item.id
            parsed += item
        }

        val validated = AgentPlanItem.validateAll(parsed)
            .getOrElse { return "失败：${it.message}" }

        // 先读旧清单：既用于 diff，也用于状态转移校验。
        // 全量覆盖最危险的副作用是模型少写一条已完成项 = 静默删除，diff 是唯一的刹车。
        val previous = store.loadBlocking(conversationId)
        AgentPlanItem.validateTransitions(previous, validated, reopenIds)
            .getOrElse { return "失败：${it.message}" }

        // 范围漂移检测必须在写库前算：写库后 previous 就被覆盖了。
        // 只在执行阶段生效 —— 规划阶段本来就在改计划，不需要重新批准。
        val scopeChange = if (enforceApprovedScope) structuralChangeText(previous, validated) else null

        store.replaceBlocking(conversationId, validated, nowMillis)

        // 清空也是一次"删除 N 条"，同样算超出已批准范围，必须在 empty 分支之前处理。
        if (scopeChange != null) {
            return buildString {
                append(SCOPE_CHANGE_PREFIX)
                append(scopeChange)
                append("。执行已暂停并退回待批准；请向用户说明变更原因，等待重新批准后再继续。\n")
                if (validated.isNotEmpty()) append(buildResultText(validated, previous))
            }
        }

        if (validated.isEmpty()) {
            return if (previous.isEmpty()) "已清空任务清单。" else "已清空任务清单（删除 ${previous.size} 条）。"
        }

        return buildResultText(validated, previous)
    }

    /** 成功回执：模型据此知道自己刚才提交什么、改了什么、下一步该做什么。 */
    private fun buildResultText(
        items: List<AgentPlanItem>,
        previous: List<AgentPlanItem>,
    ): String {
        val (done, total) = AgentPlanItem.progressOf(items)
        val cancelled = AgentPlanItem.cancelledCount(items)
        val running = items.firstOrNull { it.status == AgentPlanStatus.IN_PROGRESS }
        val diff = diffText(previous, items)
        val skippedInProgress = items.count { item ->
            item.status == AgentPlanStatus.COMPLETED &&
                previous.firstOrNull { it.id == item.id }?.status == AgentPlanStatus.PENDING
        }
        return buildString {
            append("任务清单已更新：$done/$total 完成")
            if (cancelled > 0) append("，$cancelled 项已取消")
            append("。")
            if (diff != null) append("本次变更：$diff。")
            if (skippedInProgress > 0) {
                append("注意：$skippedInProgress 条从「待开始」直接标为「已完成」，跳过了 in_progress；请确认确实已执行并验证。")
            }
            if (running != null) {
                append("当前进行中：${running.subject}。")
            } else {
                val next = items.firstOrNull { it.status == AgentPlanStatus.PENDING }
                if (next != null) {
                    append("下一条待开始：${next.subject}（开始前请把它的 status 置为 in_progress）。")
                } else if (cancelled > 0) {
                    append("所有条目均已结束，其中 $cancelled 条被取消；如任务未完成请勿据此宣称完成。")
                } else {
                    append("没有待开始或进行中的条目。")
                }
            }
            append("\n")
            items.forEachIndexed { index, item ->
                append("\n${index + 1}. [${item.status.label}] ${item.subject}")
            }
        }
    }

    /**
     * 本次全量覆盖相对上一版的变更。
     *
     * 只统计 id 维度：全量覆盖下"少了哪条"是最容易被模型忽略、也最该被指出的信息。
     * 没有任何变化时返回 null（模型重复提交同一份清单不必产生噪音）。
     */
    private fun diffText(
        previous: List<AgentPlanItem>,
        current: List<AgentPlanItem>,
    ): String? {
        if (previous.isEmpty()) return null
        val before = previous.associateBy { it.id }
        val after = current.associateBy { it.id }
        val added = current.count { it.id !in before }
        val removed = previous.count { it.id !in after }
        val updated = current.count { item ->
            before[item.id]?.let { old -> old.status != item.status || old.subject != item.subject } == true
        }
        if (added == 0 && removed == 0 && updated == 0) return null
        return buildList {
            if (added > 0) add("新增 $added 条")
            if (removed > 0) add("删除 $removed 条")
            if (updated > 0) add("更新 $updated 条")
        }.joinToString("，")
    }

    /**
     * 执行期相对"用户批准的那一版"的结构性变更。
     *
     * 与 [diffText] 的区别：diff 描述"这次改了什么"（含状态），这里只描述
     * **会改变工作范围**的改动 —— 新增、删除、改写步骤文本。
     * 只推进 status 是执行的正常动作，不算范围变更，否则每完成一步都要重新批准。
     *
     * 按 **subject 多重集**比较而不是按 id：模型没给 id 时 `create()` 会用
     * `step-{index}` 生成位置 ID，删掉中间一条会让后面所有 id 平移 ——
     * 按 id 比会误报成"整段重写"，把正常执行打断。
     *
     * 返回 null 表示范围未变。
     */
    fun structuralChangeText(
        previous: List<AgentPlanItem>,
        current: List<AgentPlanItem>,
    ): String? {
        // 首次建清单不算"超出批准范围"：执行阶段从空清单起步是正常的。
        if (previous.isEmpty()) return null
        val before = previous.groupingBy { it.subject }.eachCount()
        val after = current.groupingBy { it.subject }.eachCount()
        val added = after.entries.sumOf { (subject, count) ->
            (count - (before[subject] ?: 0)).coerceAtLeast(0)
        }
        val removed = before.entries.sumOf { (subject, count) ->
            (count - (after[subject] ?: 0)).coerceAtLeast(0)
        }
        if (added == 0 && removed == 0) return null
        return buildList {
            if (added > 0) add("新增 $added 条")
            if (removed > 0) add("删除 $removed 条")
        }.joinToString("，")
    }

    /**
     * 生成注入用的清单概览（一行）。
     *
     * 放在**上下文尾部**而非系统提示：系统提示是请求前缀的开头，逐轮变化会让整段历史
     * 失去缓存。清单概览需要随执行进度更新，只能走尾部。
     *
     * 需要完整步骤时用 [checklistText]：概览只回答"到第几步了"，不回答"还剩哪几步"。
     */
    fun overviewText(items: List<AgentPlanItem>): String? {
        if (items.isEmpty()) return null
        val (done, total) = AgentPlanItem.progressOf(items)
        if (total == 0) return null
        val cancelled = AgentPlanItem.cancelledCount(items)
        val running = items.firstOrNull { it.status == AgentPlanStatus.IN_PROGRESS }
        val head = if (running != null) {
            "进行中：${running.subject}"
        } else {
            items.firstOrNull { it.status == AgentPlanStatus.PENDING }?.let { "下一条：${it.subject}" }
                ?: if (cancelled > 0) "已结束（含取消）" else "已全部结束"
        }
        return buildString {
            append("$OVERVIEW_PREFIX $done/$total 完成")
            if (cancelled > 0) append(" · $cancelled 已取消")
            append(" · $head")
        }
    }

    /**
     * 生成注入用的**完整清单**（多行）。
     *
     * 与 [overviewText] 的分工：
     * - 概览给 UI/日志，一行够用；
     * - 完整清单给模型，用于**压缩后**与**每次 run 开始**恢复"还剩哪几步"。
     *   只给一行会让压缩后的模型知道进度、却不知道后续步骤，只能重写计划或重复已完成工作。
     *
     * @param maxChars 字符预算；超出时按条截断并明确标注省略数量，绝不静默截半行。
     */
    fun checklistText(items: List<AgentPlanItem>, maxChars: Int = 2_000): String? {
        if (items.isEmpty()) return null
        val (done, total) = AgentPlanItem.progressOf(items)
        val cancelled = AgentPlanItem.cancelledCount(items)
        val header = buildString {
            append("[当前任务清单（权威状态，来自数据库；与历史冲突时以此为准）]\n")
            append("进度：$done/$total 完成")
            if (cancelled > 0) append("，$cancelled 项已取消")
            append("\n")
        }
        val footer = "\n请从「进行中」项继续；不要重复「已完成」项。"
        val lines = ArrayList<String>(items.size)
        var used = header.length + footer.length
        var omitted = 0
        for ((index, item) in items.withIndex()) {
            val line = "${index + 1}. [${item.status.label}] ${item.subject}"
            // 单条最短也要能放下；否则宁可提前截断，也不产生半行。
            if (lines.size >= MAX_CHECKLIST_LINES || used + line.length + 1 > maxChars) {
                omitted = items.size - index
                break
            }
            lines += line
            used += line.length + 1
        }
        return buildString {
            append(header)
            lines.forEach { append(it).append("\n") }
            if (omitted > 0) {
                append("…（另有 $omitted 项未显示，完整清单共 ${items.size} 项）\n")
            }
            append(footer)
        }
    }

    /**
     * 计划正文 + 清单的合并注入文本。
     *
     * 为什么需要正文：`exit_plan_mode` 的正文只存在于当次工具回执里。上下文一旦压缩或被
     * 硬裁剪，模型就只剩步骤清单 —— 忘了目标、约束和验收标准，执行到一半开始跑偏。
     * 概览/清单回答"做到哪一步"，正文回答"为什么做、什么算做完"。
     *
     * 没有正文时退化为纯清单，调用方无需分支。
     */
    fun planContextText(
        markdown: String?,
        items: List<AgentPlanItem>,
        maxPlanChars: Int = MAX_PLAN_CONTEXT_CHARS,
    ): String? {
        val checklist = checklistText(items)
        val plan = markdown?.trim()?.takeIf { it.isNotEmpty() } ?: return checklist
        val body = if (plan.length > maxPlanChars) {
            plan.take(maxPlanChars) + "\n……（计划正文已截断，完整内容见提交时的工具回执）"
        } else {
            plan
        }
        return buildString {
            append("[已批准计划 · 目标与约束（来自数据库；与历史冲突时以此为准）]\n")
            append(body)
            if (checklist != null) {
                append("\n\n")
                append(checklist)
            }
        }
    }
}
