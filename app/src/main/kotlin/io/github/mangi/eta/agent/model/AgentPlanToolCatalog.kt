package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.plan.AgentPlanTool
import org.json.JSONArray

/**
 * 任务清单工具的目录入口。
 *
 * 与其它 `*ToolCatalog` 保持同一形状（`appendTo`），这样总目录 [AgentToolCatalog]
 * 只需多一行就能挂上，不必理解清单的内部结构。
 *
 * 只在 [writable] 时挂载：只读会话（如子代理的受限运行）不应改写主任务的清单。
 */
internal object AgentPlanToolCatalog {
    fun appendTo(tools: JSONArray, writable: Boolean = true) {
        if (!writable) return
        tools.put(AgentPlanTool.schema())
    }
}
