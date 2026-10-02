package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import org.json.JSONArray
import org.junit.Test

/**
 * 量测工具定义（schema）在 `rawEstimate` 口径下占多少 token。
 *
 * 背景：真机 logcat 显示 `round=1, messages=9 → in=59,524`，
 * 即"零上下文时"仍有约 5.95 万 token 的固定开销。本测试用于分离出其中
 * 属于工具 schema 的那一部分——它不会被上下文压缩影响。
 */
internal class ToolSchemaFootprintTest {
    @Test
    fun measureToolSchemaFootprint() {
        val scenarios = listOf(
            "全部工具开启（root 可用，含敏感读/写、浏览器、记忆、终端）" to AgentToolCatalog.build(
                terminalTools = true,
                browserTools = true,
                deviceDirectTools = true,
                deviceSensitiveReadTools = true,
                deviceSensitiveActionTools = true,
                skillGitHubDiscovery = true,
                skillGitHubInstall = true,
                memoryTools = true,
                memoryWritable = true,
                capabilities = AgentToolCapabilities(rootAvailable = true),
            ),
            "精简（仅直接工具 + 手势，无敏感/浏览器/记忆/终端）" to AgentToolCatalog.build(
                terminalTools = false,
                browserTools = false,
                deviceDirectTools = true,
                deviceSensitiveReadTools = false,
                deviceSensitiveActionTools = false,
                skillGitHubDiscovery = false,
                skillGitHubInstall = false,
                memoryTools = false,
                capabilities = AgentToolCapabilities(rootAvailable = false),
            ),
            "空工具集（仅用于对照基线）" to JSONArray(),
        )

        println("========== TOOL SCHEMA FOOTPRINT ==========")
        for ((label, tools) in scenarios) {
            val json = tools.toString()
            val chars = json.length
            val tokens = AgentContextBudget.textTokens(json) + 16
            println("--- $label ---")
            println("  tool count        = ${tools.length()}")
            println("  json chars        = $chars")
            println("  rawEstimate(tools)= $tokens")
            println("  chars/token       = ${if (tokens > 0) "%.2f".format(chars.toDouble() / tokens) else "-"}")
        }
        println("=========================================")
    }
}
