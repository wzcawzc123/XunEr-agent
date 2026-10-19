package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.autotask.AutoTaskTool
import io.github.mangi.eta.agent.plan.AgentPlanModeTool
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
 * 只在 [writable] 时挂载任务清单：只读运行（如受限子代理）不应改写主任务的清单。
 * 自动任务工具由 [autoTask] 独立控制 —— 它与清单无关，不能因为某个入口没有会话标识
 * （清单不可用）就一起消失。
 */
internal object AgentPlanToolCatalog {
    fun appendTo(
        tools: JSONArray,
        writable: Boolean = true,
        autoTask: Boolean = true,
    ) {
        if (writable) {
            val schema = AgentPlanTool.schema()
            tools.put(
                AgentToolSchema.function(
                    name = schema.getString("name"),
                    description = schema.getString("description"),
                    parameters = schema.getJSONObject("parameters"),
                ),
            )
            // 计划模式的两个闸门：进入 / 提交审批。具体能否调用由阶段策略在执行层判定。
            for (planTool in listOf(AgentPlanModeTool.enterSchema(), AgentPlanModeTool.exitSchema())) {
                tools.put(
                    AgentToolSchema.function(
                        name = planTool.getString("name"),
                        description = planTool.getString("description"),
                        parameters = planTool.getJSONObject("parameters"),
                    ),
                )
            }
        }
        if (!autoTask) return
        val autoTaskSchema = AutoTaskTool.schema()
        tools.put(
            AgentToolSchema.function(
                name = autoTaskSchema.getString("name"),
                description = autoTaskSchema.getString("description"),
                parameters = autoTaskSchema.getJSONObject("parameters"),
            ),
        )
        for (extra in listOf(AutoTaskTool.listSchema(), AutoTaskTool.deleteSchema())) {
            tools.put(
                AgentToolSchema.function(
                    name = extra.getString("name"),
                    description = extra.getString("description"),
                    parameters = extra.getJSONObject("parameters"),
                ),
            )
        }
    }
}
