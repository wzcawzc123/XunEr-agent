package io.github.mangi.eta.agent.model

import org.json.JSONArray
import io.github.mangi.eta.agent.tool.AgentToolCapabilities

/** 声明模型可见的工具及其 JSON Schema；不包含任何执行逻辑。 */
internal object AgentToolCatalog {
    fun build(
        terminalTools: Boolean,
        browserTools: Boolean,
        deviceDirectTools: Boolean = true,
        deviceSensitiveReadTools: Boolean = false,
        deviceSensitiveActionTools: Boolean = false,
        skillGitHubDiscovery: Boolean = false,
        skillGitHubInstall: Boolean = false,
        memoryTools: Boolean = false,
        memoryWritable: Boolean = true,
        /** 是否挂载任务清单工具；无持久会话的入口（语音/外部助手）为 false。 */
        planTools: Boolean = true,
        capabilities: AgentToolCapabilities = AgentToolCapabilities(rootAvailable = true),
        localWebSearch: Boolean = true,
        /**
         * 当前计划阶段。只读阶段（PLANNING/REVIEW）只暴露只读工具，
         * 见 [io.github.mangi.eta.agent.plan.AgentPlanModePolicy.exposes]。
         */
        planPhase: io.github.mangi.eta.agent.plan.AgentPlanPhase =
            io.github.mangi.eta.agent.plan.AgentPlanPhase.NORMAL,
    ): JSONArray =
        capabilities.project(JSONArray().also { tools ->
            AgentContextAppToolCatalog.appendTo(tools)
            AgentGestureToolCatalog.appendTo(tools)
            AgentTextSystemToolCatalog.appendTo(tools)
            AgentDeviceToolCatalog.appendTo(
                tools,
                directTools = deviceDirectTools,
                sensitiveReadTools = deviceSensitiveReadTools,
                sensitiveActionTools = deviceSensitiveActionTools,
            )
            if (browserTools) {
                AgentWebToolCatalog.appendTo(tools, includeSearch = localWebSearch)
                AgentBrowserToolCatalog.appendTo(tools)
            }
            AgentSkillToolCatalog.appendTo(
                tools,
                githubDiscovery = skillGitHubDiscovery,
                githubInstall = skillGitHubInstall,
            )
            if (memoryTools) AgentMemoryToolCatalog.appendTo(tools, writable = memoryWritable)
            AgentPlanToolCatalog.appendTo(tools, writable = planTools)
            if (terminalTools) {
                AgentFileVisionToolCatalog.appendTo(tools)
                AgentTerminalToolCatalog.appendTo(tools)
                AgentAndroguardToolCatalog.appendTo(tools)
                AgentFileToolCatalog.appendTo(tools)
            }
        }).let { projected -> filterForPhase(projected, planPhase) }

    /**
     * 只读阶段把写工具从**模型可见的目录**里整个去掉。
     *
     * 运行时拦截（`AgentPlanModePolicy.isBlocked`）是兜底；这里做的是更靠前的一层：
     * 小模型看到 `todo_write` 就会选它，列表里根本没有就不会误用。
     * 任何不在只读白名单里的工具（含 MCP 扩展工具）在只读阶段一律不暴露，
     * 与运行时的白名单判定保持同一口径。
     */
    private fun filterForPhase(
        tools: JSONArray,
        planPhase: io.github.mangi.eta.agent.plan.AgentPlanPhase,
    ): JSONArray {
        if (!planPhase.isReadOnly) return tools
        val kept = JSONArray()
        for (index in 0 until tools.length()) {
            val tool = tools.optJSONObject(index) ?: continue
            val name = tool.optJSONObject("function")?.optString("name").orEmpty()
            if (name.isNotEmpty() && io.github.mangi.eta.agent.plan.AgentPlanModePolicy.exposes(planPhase, name)) {
                kept.put(tool)
            }
        }
        return kept
    }
}
