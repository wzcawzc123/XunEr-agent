package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.plan.AgentPlanTool
import org.json.JSONArray

/**
 * 任务清单工具的目录入口。
 *
 * 与其它 `*ToolCatalog` 保持同一形状（`appendTo`），这样总目录 [AgentToolCatalog]
 * 只需多一行就能挂上。
 *
 * 注意：目录里的每一项必须是 `{"type":"function","function":{name,description,parameters}}`，
 * 因为 [AgentToolCapabilities.project] 会读 `tool.getJSONObject("function")`。
 * [AgentPlanTool.schema] 返回的是裸定义（便于直接断言），在这里用 [AgentToolSchema.function] 包一层。
 *
 * 只在 [writable] 时挂载：只读运行（如受限子代理）不应改写主任务的清单。
 */
internal object AgentPlanToolCatalog {
    fun appendTo(tools: JSONArray, writable: Boolean = true) {
        if (!writable) return
        val schema = AgentPlanTool.schema()
        tools.put(
            AgentToolSchema.function(
                name = schema.getString("name"),
                description = schema.getString("description"),
                parameters = schema.getJSONObject("parameters"),
            ),
        )
    }
}
