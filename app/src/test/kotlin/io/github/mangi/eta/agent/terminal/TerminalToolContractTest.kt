package io.github.mangi.eta.agent.terminal

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentToolCallValidator
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * terminal 参数合同的**有意分层**：能力目录（schema）与运行时校验并不完全对称。
 *
 * - schema 只暴露规范写法：`action=exec`、`environment=android|linux`（linux 是抽象值，
 *   由设置决定落到 alpine 还是 debian）——引导模型走正路，不让它挑发行版、用旧名；
 * - 运行时保留容错：`canonicalAction` 接受 `open_and_exec`，`legacyValue` 接受直接指定的
 *   `alpine`/`debian` —— 不破坏既有调用方与历史链路。
 *
 * 因此「能力目录拒绝、运行时接受」是**设计**，不是不同步。
 *
 * 留痕（2026-10-07 全量审查）：本次审查一度把这种不对称误判为"上游漏同步 schema"，
 * 把 open_and_exec 补进 actions 表、把 alpine/debian 补进 enum，被
 * RootShellTerminalControllerTest 与 AgentToolCatalogTest 拦下后回滚。下面这些断言
 * 就是为了让同样的误判下次跑不过。
 */
class TerminalToolContractTest {

    @Test
    fun capabilityCatalogRejectsLegacyAliasWhileRuntimeKeepsCompatibility() {
        assertNotNull(
            "能力目录不应暴露 open_and_exec（引导模型用 exec）",
            schemaVerdict("""{"action":"open_and_exec","command":"id"}"""),
        )
        assertNull(
            "运行时必须继续兼容 open_and_exec",
            TerminalToolContract.validate(JSONObject("""{"action":"open_and_exec","command":"id"}""")),
        )
    }

    @Test
    fun capabilityCatalogExposesAbstractLinuxWhileRuntimeAcceptsConcreteDistributions() {
        assertNull(
            "linux 是面向模型的抽象值",
            schemaVerdict("""{"action":"exec","command":"id","environment":"linux"}"""),
        )
        assertNotNull(
            "具体发行版名不进能力目录",
            schemaVerdict("""{"action":"exec","command":"id","environment":"debian"}"""),
        )
        assertNull(
            "但运行时容错接受它",
            TerminalToolContract.validate(JSONObject("""{"action":"exec","command":"id","environment":"debian"}""")),
        )
        assertNotNull(
            "真正的非法取值，运行时也必须拒绝",
            TerminalToolContract.validate(JSONObject("""{"action":"exec","command":"id","environment":"nope"}""")),
        )
    }

    @Test
    fun schemaAndRuntimeAgreeOnOrdinaryCalls() {
        listOf(
            """{"action":"open"}""",
            """{"action":"exec","command":"id"}""",
            """{"action":"exec","command":"id","environment":"linux"}""",
            """{"action":"daemon_list"}""",
            """{"action":"daemon_logs","task_id":"t"}""",
            """{"action":"read_async_result","job_id":"j"}""",
        ).forEach { argumentsJson ->
            assertNull("schema 应放行 $argumentsJson", schemaVerdict(argumentsJson))
            assertNull("运行时也应放行 $argumentsJson", TerminalToolContract.validate(JSONObject(argumentsJson)))
        }
    }

    @Test
    fun perActionFieldWhitelistRejectsForeignFieldsInBothLayers() {
        listOf(
            """{"action":"daemon_logs","task_id":"t","environment":"linux"}""",
            """{"action":"daemon_stop","task_id":"t","identity":"root"}""",
            """{"action":"read_async_result","job_id":"j","offset_chars":0,"environment":"linux"}""",
            """{"action":"open","command":"id"}""",
        ).forEach { argumentsJson ->
            assertNotNull("schema 应拒绝 $argumentsJson", schemaVerdict(argumentsJson))
            assertNotNull("运行时也应拒绝 $argumentsJson", TerminalToolContract.validate(JSONObject(argumentsJson)))
        }
    }

    @Test
    fun unknownActionAndMissingRequiredFieldsStillRejected() {
        assertNotNull(schemaVerdict("""{"action":"nope"}"""))
        assertNotNull(schemaVerdict("""{"action":"exec"}"""))
        assertNotNull(TerminalToolContract.validate(JSONObject("""{"action":"nope"}""")))
        assertNotNull(TerminalToolContract.validate(JSONObject("""{"action":"exec"}""")))
    }

    private fun schemaVerdict(argumentsJson: String): String? =
        validator().validate(
            AgentModelClient.ToolCall(id = "call-1", name = "terminal", argumentsJson = argumentsJson),
        )

    private fun validator(): AgentToolCallValidator = AgentToolCallValidator(
        JSONArray().put(
            JSONObject()
                .put("type", "function")
                .put(
                    "function",
                    JSONObject().put("name", "terminal").put("parameters", TerminalToolContract.schema()),
                ),
        ),
    )
}
