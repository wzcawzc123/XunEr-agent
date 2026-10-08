package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolCallValidatorTest {
    @Test
    fun xmlStyleStickyResidueIsRejectedWithTargetedGuidance() {
        // 2026-10-08 真机取证：模型把参数写成 XML 形态并粘包，action 值成了
        // `exec><parameter = command>…`。落在枚举字段会被取值校验挡住，
        // 落到 command 这类自由文本字段却会原样交给 shell —— 必须在统一入口拦下。
        val validator = validator(xmlGuardSchema())

        val message = validator.validate(
            call("""{"action":"exec><parameter = command>/tmp/ub 2>&1","command":"echo hi"}""")
        )

        assertNotNull("粘包残渣必须被拒绝", message)
        assertTrue("应点明 XML 残留：$message", message.orEmpty().contains("XML 标签残留"))
        assertTrue("应点明字段名：$message", message.orEmpty().contains("action"))
    }

    @Test
    fun legitimateXmlTextArgumentsAreNotBlocked() {
        // 反例：真的在处理 XML/HTML（grep 搜标签、写 XML 文件）不得被误伤。
        val validator = validator(xmlGuardSchema())

        assertNull(validator.validate(call("""{"command":"grep '<parameter' config.xml"}""")))
        assertNull(validator.validate(call("""{"command":"echo '<a>text</a>' > out.xml"}""")))
    }

    private fun xmlGuardSchema(): JSONObject = JSONObject(
        """
        {
          "type": "object",
          "properties": {
            "action": {"type": "string"},
            "command": {"type": "string"}
          }
        }
        """.trimIndent()
    )

    @Test
    fun localRefAndAnyOfAcceptEitherDeclaredShape() {
        val validator = validator(
            JSONObject(
                """
                {
                  "type": "object",
                  "anyOf": [
                    {"required": ["query"]},
                    {"required": ["filter"]}
                  ],
                  "properties": {
                    "query": {"${'$'}ref": "#/${'$'}defs/query"},
                    "filter": {"type": "object"}
                  },
                  "${'$'}defs": {
                    "query": {"type": "string", "minLength": 1}
                  }
                }
                """.trimIndent()
            )
        )

        assertNull(validator.validate(call("""{"query":"Eta"}""")))
        assertNull(validator.validate(call("""{"filter":{}}""")))
        assertNotNull(validator.validate(call("{}")))
        assertNotNull(validator.validate(call("""{"query":""}""")))
    }

    @Test
    fun oneOfRequiresExactlyOneMatchingBranch() {
        val validator = validator(
            JSONObject(
                """
                {
                  "type": "object",
                  "oneOf": [
                    {"required": ["left"]},
                    {"required": ["right"]}
                  ]
                }
                """.trimIndent()
            )
        )

        assertNull(validator.validate(call("""{"left":true}""")))
        assertNotNull(validator.validate(call("{}")))
        assertNotNull(validator.validate(call("""{"left":true,"right":true}""")))
    }

    @Test
    fun conditionAndAdditionalPropertiesAreValidated() {
        val validator = validator(
            JSONObject(
                """
                {
                  "type": "object",
                  "properties": {
                    "mode": {"enum": ["text", "count"]},
                    "value": {}
                  },
                  "required": ["mode", "value"],
                  "additionalProperties": false,
                  "if": {"properties": {"mode": {"const": "count"}}},
                  "then": {"properties": {"value": {"type": "integer"}}},
                  "else": {"properties": {"value": {"type": "string"}}}
                }
                """.trimIndent()
            )
        )

        assertNull(validator.validate(call("""{"mode":"count","value":2}""")))
        assertNull(validator.validate(call("""{"mode":"text","value":"two"}""")))
        assertNotNull(validator.validate(call("""{"mode":"count","value":"2"}""")))
        assertNotNull(validator.validate(call("""{"mode":"text","value":"two","extra":true}""")))
    }

    @Test
    fun booleanSchemasAreNotSilentlyIgnored() {
        val validator = validator(
            JSONObject(
                """
                {
                  "type": "object",
                  "properties": {
                    "allowed": true,
                    "blocked": false
                  }
                }
                """.trimIndent()
            )
        )

        assertNull(validator.validate(call("""{"allowed":{"anything":true}}""")))
        assertNotNull(validator.validate(call("""{"blocked":1}""")))
    }

    @Test
    fun missingRequiredFieldsReportWhatWasActuallyReceived() {
        val validator = validator(
            JSONObject(
                """
                {
                  "type": "object",
                  "required": ["mode", "revision"],
                  "properties": {
                    "mode": {"type": "string"},
                    "revision": {"type": "string"},
                    "content": {"type": "string"}
                  }
                }
                """.trimIndent()
            )
        )

        // 只缺 revision：报错要带上"实际收到了什么"，用于区分"模型没发"与"链路把参数丢了/串了"。
        val missingRevision = validator.validate(call("""{"mode":"append","content":"1234567890"}"""))
        org.junit.Assert.assertNotNull(missingRevision)
        org.junit.Assert.assertTrue(missingRevision!!.contains("缺少必填字段 revision"))
        org.junit.Assert.assertTrue(missingRevision.contains("已收到字段"))
        org.junit.Assert.assertTrue(missingRevision.contains("mode"))
        org.junit.Assert.assertTrue(missingRevision.contains("content"))
        // 只暴露形状，不把取值写进错误信息。
        org.junit.Assert.assertFalse(missingRevision.contains("append"))
        org.junit.Assert.assertFalse(missingRevision.contains("1234567890"))

        // 多个必填一起缺时应一次列全，而不是只报第一个。
        val bothMissing = validator.validate(call("""{}"""))
        org.junit.Assert.assertNotNull(bothMissing)
        org.junit.Assert.assertTrue(bothMissing!!.contains("mode"))
        org.junit.Assert.assertTrue(bothMissing.contains("revision"))
        org.junit.Assert.assertTrue(bothMissing.contains("已收到字段：（无）"))
    }

    @Test
    fun redactedPayloadIsGuidedInsteadOfReportedAsMissingField() {
        val validator = validator(
            JSONObject("""{"type":"object","required":["path"],"properties":{"path":{"type":"string"}}}""")
        )
        val redacted = call(
            """{"_redacted":true,"_note":"占位","_fields":{"path":"str:44"}}"""
        )

        val message = validator.validate(redacted)

        org.junit.Assert.assertNotNull(message)
        org.junit.Assert.assertTrue(message!!.contains("脱敏占位"))
        org.junit.Assert.assertTrue(message.contains("重新"))
        org.junit.Assert.assertFalse(message.contains("缺少必填字段"))
        org.junit.Assert.assertTrue(validator.isRedactedReplay(redacted))
        org.junit.Assert.assertFalse(validator.isRedactedReplay(call("""{"path":"/sdcard/a.png"}""")))
    }

    @Test
    fun oneOfWithMultipleMatchingBranchesReportsTheCount() {
        val validator = validator(
            JSONObject(
                """
                {
                  "type": "object",
                  "oneOf": [
                    {"required": ["left"]},
                    {"required": ["right"]}
                  ]
                }
                """.trimIndent()
            )
        )

        val message = validator.validate(call("""{"left":true,"right":true}"""))
        assertNotNull(message)
        assertTrue("须报出互斥分支冲突而非笼统描述", message!!.contains("互斥分支"))
    }

    @Test
    fun retiredToolNameGetsMigrationGuidanceInsteadOfGenericMissingTool() {
        val validator = validator(JSONObject("""{"type":"object"}"""))

        val guided = validator.validate(
            AgentModelClient.ToolCall(
                id = "call-1",
                name = "run_command",
                argumentsJson = """{"command":"id"}""",
            )
        )
        assertNotNull(guided)
        assertTrue("须给出迁移目标 terminal", guided!!.contains("terminal"))

        val generic = validator.validate(
            AgentModelClient.ToolCall(id = "call-2", name = "no_such_tool", argumentsJson = "{}")
        )
        assertEquals("未声明工具仍走通用文案", "工具未在本次运行的能力目录中声明", generic)
    }

    @Test
    fun notRejectionNamesTheOffendingFieldInsteadOfBeingOpaque() {
        // 复刻 terminal 的真实形态：oneOf 分支 + not 禁掉本 action 不该有的字段。
        // 真机实测（v3.8.1）给 daemon_logs 多传 environment 时，旧文案是
        // "分支N：arguments 符合了 not 禁止的 Schema"，完全看不出是哪个字段多余。
        val validator = validator(
            JSONObject(
                """
                {
                  "type": "object",
                  "properties": {
                    "action": {"type": "string", "enum": ["exec", "daemon_logs"]},
                    "command": {"type": "string"},
                    "task_id": {"type": "string"},
                    "environment": {"type": "string", "enum": ["android", "linux"]}
                  },
                  "required": ["action"],
                  "oneOf": [
                    {
                      "properties": {"action": {"enum": ["exec"]}},
                      "required": ["action", "command"],
                      "not": {"anyOf": [{"required": ["task_id"]}, {"required": ["environment"]}]}
                    },
                    {
                      "properties": {"action": {"enum": ["daemon_logs"]}},
                      "required": ["action", "task_id"],
                      "not": {"anyOf": [{"required": ["command"]}, {"required": ["environment"]}]}
                    }
                  ]
                }
                """.trimIndent()
            )
        )

        val message = validator.validate(call("""{"action":"daemon_logs","task_id":"t","environment":"linux"}"""))
        assertNotNull(message)
        assertTrue("必须指出违规字段名，而不是笼统的 not 文案", message!!.contains("environment"))
        assertTrue(message.contains("不接受"))
    }

    @Test
    fun unsupportedActionValueIsReportedDirectlyWithAllowedSet() {
        // 值非法（action 拼错 / 用旧名）是最常见的调用错误：直接点明值不被接受并给出
        // 合法取值，比罗列一堆无关分支的失败原因更有用（真机实测：open_and_exec、foobar
        // 都只会得到"某些分支不接受 command"之类的解释，模型还得自己比对取值表）。
        val validator = validator(
            JSONObject(
                """
                {
                  "type": "object",
                  "properties": {
                    "action": {"type": "string", "enum": ["exec", "close"]},
                    "command": {"type": "string"},
                    "session_id": {"type": "string"}
                  },
                  "required": ["action"],
                  "oneOf": [
                    {"properties": {"action": {"enum": ["exec"]}}, "required": ["action", "command"]},
                    {"properties": {"action": {"enum": ["close"]}}, "required": ["action", "session_id"]}
                  ]
                }
                """.trimIndent()
            )
        )

        val message = validator.validate(call("""{"action":"open_and_exec"}"""))
        assertNotNull(message)
        assertTrue("须直接点明该值不被接受：$message", message!!.contains("不被接受"))
        assertTrue("须回显收到的值：$message", message.contains("open_and_exec"))
        assertTrue("须给出合法取值：$message", message.contains("合法取值") && message.contains("exec"))
        assertFalse("不必罗列无关分支的原因：$message", message.contains("各分支失败原因"))
    }

    @Test
    fun theBranchMatchingTheRequestedActionIsReportedFirst() {
        // 真机实测（v3.8.2）：传合法的 action=exec 但缺 command 时，exec 分支的
        // 原因排在第二位；terminal 有 8 个分支，一旦掉出前 3 条，模型就只看得到
        // 与自己调用无关的分支解释。本测试锁住"相关分支必须排第一"。
        val validator = validator(
            JSONObject(
                """
                {
                  "type": "object",
                  "properties": {
                    "action": {"type": "string", "enum": ["open", "exec", "close", "daemon_list"]},
                    "command": {"type": "string"},
                    "session_id": {"type": "string"}
                  },
                  "required": ["action"],
                  "oneOf": [
                    {"properties": {"action": {"enum": ["open"]}}, "required": ["action"]},
                    {"properties": {"action": {"enum": ["exec"]}}, "required": ["action", "command"]},
                    {"properties": {"action": {"enum": ["close"]}}, "required": ["action", "session_id"]},
                    {"properties": {"action": {"enum": ["daemon_list"]}}, "required": ["action"]}
                  ]
                }
                """.trimIndent()
            )
        )

        val message = validator.validate(call("""{"action":"exec"}"""))
        assertNotNull(message)
        val reasons = message!!.substringAfter("各分支失败原因：")
        assertTrue("与请求 action 同名的分支必须排第一：$message", reasons.startsWith("action=exec"))
        assertTrue("须说明缺什么：$message", reasons.contains("缺少必填字段 command"))
    }

    @Test
    fun emptyPathIsRejectedBeforeExecutionForFileTools() {
        // 真机实测：path 只声明了 maxLength、没有 minLength，空路径会被放行到执行层，
        // 报成「NOT_REGULAR_FILE：目标不是普通文件」，看不出是路径为空。
        val validator = AgentToolCallValidator(
            JSONArray().also { AgentFileToolCatalog.appendTo(it) },
        )
        val message = validator.validate(
            AgentModelClient.ToolCall(id = "call-1", name = "read_file", argumentsJson = """{"path":""}"""),
        )
        assertNotNull("空路径应在能力目录层被拦：$message", message)
        assertTrue("错误须指向 path：$message", message!!.contains("path"))
    }

    private fun validator(parameters: JSONObject): AgentToolCallValidator =
        AgentToolCallValidator(
            JSONArray().put(
                JSONObject()
                    .put("type", "function")
                    .put(
                        "function",
                        JSONObject()
                            .put("name", TOOL_NAME)
                            .put("parameters", parameters),
                    )
            )
        )

    private fun call(argumentsJson: String) = AgentModelClient.ToolCall(
        id = "call-1",
        name = TOOL_NAME,
        argumentsJson = argumentsJson,
    )

    private companion object {
        const val TOOL_NAME = "test_tool"
    }
}
