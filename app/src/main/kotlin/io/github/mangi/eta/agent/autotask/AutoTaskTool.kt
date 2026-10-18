package io.github.mangi.eta.agent.autotask

import org.json.JSONArray
import org.json.JSONObject

/**
 * `create_auto_task` 工具：让模型在对话中直接创建自动任务。
 *
 * 为什么必须有这个工具，而不是只靠设置页：
 * 用户的真实表达是「帮我盯 CI」「红了告诉我」——
 * **如果模型只能口头答应，那一轮结束它就忘了**，而用户以为有人在盯。
 * 这个工具把「口头承诺」变成「数据库里的一条记录 + 守护进程会去跑」。
 *
 * 与 dsh 的 `schedule_create` 对齐的参数语义：
 * - `title`：**必填且不从指令派生**（用户要能在列表里认出它）
 * - `after` / `every` / `daily`：时间语义；缺省表示"条件满足就跑"
 * - `condition`：Android 特有的"条件"（dsh 没有，因为桌面不做 Doze 妥协）
 */
internal object AutoTaskTool {

    const val NAME = "create_auto_task"

    fun schema(): JSONObject = JSONObject()
        .put("name", NAME)
        .put(
            "description",
            "创建一个**自动任务**：在条件满足时由后台守护进程唤醒，自动跑一轮并" +
                "把结果追加到当前会话。 " +
                "当用户说「帮我盯 X」「有结果告诉我」「红了提醒我」「过一会儿再看」时**必须调用本工具**，" +
                "不要只口头答应 —— 口头承诺在这一轮结束后就失效了。 " +
                "创建后任务默认未启用，需要用户确认（可用 enable 参数直接启用）。",
        )
        .put(
            "parameters",
            JSONObject()
                .put("type", "object")
                .put(
                    "properties",
                    JSONObject()
                        .put("title", JSONObject().put("type", "string").put("description", "任务短名，如「CI 巡检」；≤60 字"))
                        .put("prompt", JSONObject().put("type", "string").put("description", "交给 Agent 的指令正文，≤2000 字"))
                        .put(
                            "schedule",
                            JSONObject()
                                .put("type", "string")
                                .put("enum", JSONArray().put("after").put("every").put("daily").put("none"))
                                .put("description", "时间语义：after=延迟一次，every=固定间隔，daily=每天某点，none=只看条件"),
                        )
                        .put("after_minutes", JSONObject().put("type", "integer").put("description", "schedule=after 时：延迟多少分钟"))
                        .put("every_minutes", JSONObject().put("type", "integer").put("description", "schedule=every 时：间隔多少分钟"))
                        .put("at_time", JSONObject().put("type", "string").put("description", "schedule=daily 时：HH:mm，如 09:00"))
                        .put(
                            "condition",
                            JSONObject()
                                .put("type", "string")
                                .put("enum", JSONArray().put("charging_wifi_idle").put("wifi").put("any"))
                                .put("description", "触发条件（Android 后台限制用）；默认 charging_wifi_idle"),
                        )
                        .put("max_rounds", JSONObject().put("type", "integer").put("description", "单次执行轮数上限，默认 3，最多 20"))
                        .put("enable", JSONObject().put("type", "boolean").put("description", "是否立即启用；默认 false，需要用户确认")),
                )
                .put("required", JSONArray().put("title").put("prompt")),
        )

    /**
     * 执行创建。
     *
     * @param conversationId 任务绑定的会话（执行时在它里面追加一轮）
     * @return 面向模型的文本结果
     */
    fun execute(
        args: JSONObject,
        store: AutoTaskStore,
        conversationId: String?,
        nowMillis: Long,
        newId: () -> String = { "task-" + java.util.UUID.randomUUID().toString().take(8) },
    ): String {
        val title = args.optString("title").trim()
        val prompt = args.optString("prompt").trim()
        if (title.isEmpty() || prompt.isEmpty()) {
            return "失败：title 与 prompt 都是必填。"
        }

        // 关键区分：`null` 表示「不限制时间」（schedule=none 或省略），
        // 而参数写错必须报错。之前两者都返回 null，导致最常见的
        // 「只要条件满足就跑」被判成参数非法、任务根本创建不出来。
        val schedule = when (val parsed = parseSchedule(args, nowMillis)) {
            is ScheduleParse.None -> null
            is ScheduleParse.Spec -> parsed.spec
            is ScheduleParse.Invalid -> return "失败：${parsed.reason}"
        }

        val condition = when (args.optString("condition").ifBlank { "charging_wifi_idle" }) {
            "charging_wifi_idle" -> TaskCondition.CONSERVATIVE
            "wifi" -> TaskCondition.WIFI_ONLY
            "any" -> TaskCondition.ANYTIME
            else -> return "失败：condition 只能是 charging_wifi_idle / wifi / any。"
        }

        val maxRounds = args.optInt("max_rounds", 3).coerceIn(1, AgentTask.MAX_ALLOWED_ROUNDS)
        val enable = args.optBoolean("enable", false)

        val task = AgentTask.create(
            id = newId(),
            title = title,
            prompt = prompt,
            condition = condition,
            maxRounds = maxRounds,
            enabled = enable,
            conversationId = conversationId,
            nowMillis = nowMillis,
        ).getOrElse { return "失败：${it.message}" }

        // schedule 不在 create() 里（它是后加字段），这里补
        val withSchedule = task.copy(schedule = schedule)
        store.saveBlocking(withSchedule)

        val scheduleText = schedule?.describe() ?: "条件满足时"
        return buildString {
            append("已创建自动任务「${withSchedule.title}」。\n")
            append("· 时间：$scheduleText\n")
            append("· 条件：${condition.describe()}\n")
            append("· 上限：$maxRounds 轮\n")
            append("· 状态：${if (enable) "已启用" else "未启用（需用户确认）"}\n")
            append("· 结果会追加到当前会话")
            if (!enable) append("；请提醒用户开启后才会真正执行")
        }
    }

    // ---------- list / delete（对齐 dsh 四件套里的两个） ----------

    const val LIST_NAME = "list_auto_tasks"
    const val DELETE_NAME = "delete_auto_task"

    fun listSchema(): JSONObject = JSONObject()
        .put("name", LIST_NAME)
        .put("description", "列出自动任务：标题、状态、下次触发时间、上次结果。用于回答「我的任务呢」「为什么没跑」。")
        .put(
            "parameters",
            JSONObject().put("type", "object").put("properties", JSONObject()),
        )

    fun deleteSchema(): JSONObject = JSONObject()
        .put("name", DELETE_NAME)
        .put("description", "删除一条自动任务。已投递到会话的结果仍保留，不会被撤回。")
        .put(
            "parameters",
            JSONObject()
                .put("type", "object")
                .put(
                    "properties",
                    JSONObject().put("id", JSONObject().put("type", "string").put("description", "任务 id")),
                )
                .put("required", JSONArray().put("id")),
        )

    /** 列出任务。只返回"本会话的"和"未绑定会话的"，避免泄露其他会话的任务名。 */
    fun executeList(store: AutoTaskStore, conversationId: String?, nowMillis: Long): String {
        val tasks = store.allBlocking()
        if (tasks.isEmpty()) return "当前没有任何自动任务。"
        val mine = tasks.filter { it.conversationId == null || it.conversationId == conversationId }
        if (mine.isEmpty()) return "本会话没有自动任务（其他会话共有 ${tasks.size} 条）。"
        val sb = StringBuilder("自动任务 ${mine.size} 条：")
        for (t in mine) {
            val state = when {
                !t.enabled -> "未启用"
                t.isFinished -> "已执行"
                !t.active -> "已结束"
                else -> "等待中"
            }
            sb.append("\n· [").append(t.id).append("] ").append(t.title).append(" · ").append(state)
            val next = t.schedule?.nextRunAtMillis(nowMillis, t.lastRunAt)
            if (next != null && next > nowMillis) {
                val mins = (next - nowMillis) / 60_000
                sb.append(" · 约 ").append(if (mins >= 60) "${mins / 60} 小时" else "$mins 分钟").append("后")
            }
            sb.append(" · 条件 ").append(t.condition.describe())
            t.lastResult?.takeIf { it.isNotBlank() }?.let {
                sb.append("\n    上次：").append(it.lineSequence().first().take(80))
            }
        }
        return sb.toString()
    }

    /** 删除任务。返回给模型的文本；失败不抛异常。 */
    fun executeDelete(args: JSONObject, dao: () -> io.github.mangi.eta.data.db.autotask.AgentTaskDao): String {
        val id = args.optString("id").trim()
        if (id.isEmpty()) return "失败：缺少 id。"
        return runCatching {
            kotlinx.coroutines.runBlocking { dao().delete(id) }
            "已删除任务 $id。"
        }.getOrElse { "失败：${it.message ?: it.javaClass.simpleName}" }
    }

    /** 时间参数解析结果：三态明确区分「无时间限制」「有具体时间」「参数错误」。 */
    @org.jetbrains.annotations.VisibleForTesting
    internal sealed interface ScheduleParse {
        /** `schedule=none` 或未传：只看条件，不限制时间。 */
        data object None : ScheduleParse
        data class Spec(val spec: ScheduleSpec) : ScheduleParse
        data class Invalid(val reason: String) : ScheduleParse
    }

    @org.jetbrains.annotations.VisibleForTesting
    internal fun parseSchedule(args: JSONObject, nowMillis: Long): ScheduleParse {
        val kind = args.optString("schedule").ifBlank { "none" }
        return when (kind) {
            "none" -> ScheduleParse.None
            "after" -> {
                val minutes = args.optInt("after_minutes", 0)
                if (minutes <= 0) {
                    ScheduleParse.Invalid("schedule=after 需要 after_minutes > 0（分钟）")
                } else {
                    ScheduleParse.Spec(ScheduleSpec.After(minutes * 60_000L, anchor = nowMillis))
                }
            }
            "every" -> {
                val minutes = args.optInt("every_minutes", 0)
                if (minutes <= 0) {
                    ScheduleParse.Invalid("schedule=every 需要 every_minutes > 0（分钟）")
                } else {
                    ScheduleParse.Spec(ScheduleSpec.Every(minutes * 60_000L))
                }
            }
            "daily" -> {
                val raw = args.optString("at_time")
                val parts = raw.split(":")
                val h = parts.getOrNull(0)?.toIntOrNull()
                val m = parts.getOrNull(1)?.toIntOrNull()
                if (parts.size != 2 || h == null || m == null || h !in 0..23 || m !in 0..59) {
                    ScheduleParse.Invalid("schedule=daily 需要 at_time 形如 HH:mm（如 09:00）")
                } else {
                    ScheduleParse.Spec(ScheduleSpec.Daily(h, m))
                }
            }
            else -> ScheduleParse.Invalid("schedule 只能是 after / every / daily / none")
        }
    }
}
