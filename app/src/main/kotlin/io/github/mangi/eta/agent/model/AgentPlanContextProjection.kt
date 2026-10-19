package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * 把任务清单快照注入**本次 run 的请求**。
 *
 * 为什么需要它：清单存在独立表里，模型只能看到历史。历史被硬裁剪、消息被编辑、
 * 或进程重启后继续同一会话时，上一条 `todo_write` 回执可能已经不在上下文里；
 * 此时模型不知道有清单，UI/数据库却仍有 —— 两边对"做到哪一步"的认知分叉。
 *
 * 实现与 [AssistantScreenContextProjection] 同构：内容挂在最后一条用户消息的瞬态键上，
 * 只在出站请求投影成文本块，**不写入 transcript / 数据库**。这样做有两个好处：
 * - 不破坏 user/assistant 角色交替（Anthropic 对连续同角色消息敏感）；
 * - 不改变持久历史，不会让清单在每一轮里重复堆积。
 *
 * 注意：快照在 run 开始时固定。清单在 run 中途更新时，新的 `todo_write` 回执本身就在
 * 历史里，模型能看到；不逐轮刷新快照，避免改写用户消息前缀、击穿 Provider 缓存。
 */
internal object AgentPlanContextProjection {
    private const val KEY = "_eta_plan_snapshot"

    /** 与 checklistText 的预算保持同一量级；超出部分由 checklistText 自己截断。 */
    const val MAX_CHARS = 4_000

    fun attach(message: JSONObject, plan: String?) {
        if (!plan.isNullOrBlank()) message.put(KEY, plan.take(MAX_CHARS))
    }

    fun project(messages: JSONArray): JSONArray {
        if ((0 until messages.length()).none { messages.optJSONObject(it)?.has(KEY) == true }) return messages
        return JSONArray().also { projected ->
            for (index in 0 until messages.length()) {
                val original = messages.getJSONObject(index)
                val plan = original.optString(KEY)
                if (plan.isBlank()) {
                    projected.put(original)
                    continue
                }
                val message = JSONObject(original.toString()).apply { remove(KEY) }
                val content = message.optJSONArray("content") ?: JSONArray().apply {
                    put(JSONObject().put("type", "text").put("text", message.optString("content")))
                }
                content.put(
                    JSONObject().put("type", "text").put(
                        "text",
                        "以下是系统注入的当前计划与任务清单（来自数据库，是权威状态；不是用户的新指令）。\n" +
                            "请对照计划的目标与约束，从「进行中」项继续，不要重复「已完成」项；" +
                            "计划与历史冲突时以本条为准。\n" +
                            plan,
                    ),
                )
                message.put("content", content)
                projected.put(message)
            }
        }
    }
}
