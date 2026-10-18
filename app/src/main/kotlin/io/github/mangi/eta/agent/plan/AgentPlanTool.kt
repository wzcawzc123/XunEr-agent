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

    fun schema(): JSONObject = JSONObject()
        .put("name", NAME)
        .put(
            "description",
            "维护当前任务的步骤清单。**全量覆盖**：每次提交完整列表，" +
                "省略某条等于删除它；传空数组等于清空。 " +
                "仅用于需要 3 步以上、或用户明确要求列计划的复杂任务；" +
                "简单问答不要调用。 " +
                "开始一步时把它的 status 置为 in_progress（同时只能有一条），" +
                "做完置为 completed。",
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
     * @return 面向模型的文本结果：成功时回显进度与当前步骤，失败时给出可直接读懂的
     *   中文原因（不抛异常 —— 抛异常会把工具错误变成运行失败）。
     */
    fun execute(
        args: JSONObject,
        store: AgentPlanStore,
        conversationId: String,
        nowMillis: Long,
    ): String {
        if (conversationId.isBlank()) {
            return "失败：当前会话尚无标识，无法保存任务清单。"
        }

        val rawItems = args.optJSONArray("items")
            ?: return "失败：缺少必填参数 items（数组）。"

        val parsed = ArrayList<AgentPlanItem>(rawItems.length())
        for (index in 0 until rawItems.length()) {
            val raw = rawItems.optJSONObject(index)
                ?: return "失败：items[$index] 不是对象。"
            val item = AgentPlanItem.create(
                index = index,
                id = raw.optString("id", null),
                subject = raw.optString("subject", null),
                activeForm = raw.optString("activeForm", null),
                status = AgentPlanStatus.fromWire(raw.optString("status", null)),
            ).getOrElse { return "失败：${it.message}" }
            parsed += item
        }

        val validated = AgentPlanItem.validateAll(parsed)
            .getOrElse { return "失败：${it.message}" }

        store.replaceBlocking(conversationId, validated, nowMillis)

        if (validated.isEmpty()) return "已清空任务清单。"

        return buildResultText(validated)
    }

    /** 成功回执：模型据此知道自己刚才提交什么、下一步该做什么。 */
    private fun buildResultText(items: List<AgentPlanItem>): String {
        val (done, total) = AgentPlanItem.progressOf(items)
        val running = items.firstOrNull { it.status == AgentPlanStatus.IN_PROGRESS }
        return buildString {
            append("任务清单已更新：$done/$total 完成。")
            if (running != null) {
                append("当前进行中：${running.subject}。")
            } else {
                val next = items.firstOrNull { it.status == AgentPlanStatus.PENDING }
                if (next != null) {
                    append("下一条待开始：${next.subject}（开始前请把它的 status 置为 in_progress）。")
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
     * 生成注入用的清单概览（一行）。
     *
     * 放在**上下文尾部**而非系统提示：系统提示是请求前缀的开头，逐轮变化会让整段历史
     * 失去缓存。清单概览需要随执行进度更新，只能走尾部。
     */
    fun overviewText(items: List<AgentPlanItem>): String? {
        if (items.isEmpty()) return null
        val (done, total) = AgentPlanItem.progressOf(items)
        if (total == 0) return null
        val running = items.firstOrNull { it.status == AgentPlanStatus.IN_PROGRESS }
        val head = if (running != null) {
            "进行中：${running.subject}"
        } else {
            items.firstOrNull { it.status == AgentPlanStatus.PENDING }?.let { "下一条：${it.subject}" } ?: "已全部结束"
        }
        return "$OVERVIEW_PREFIX $done/$total 完成 · $head"
    }
}
