package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolCallValidatorTest {
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
    fun oneOfWithoutMatchingBranchReportsPerBranchReason() {
        // 修复前：0 个分支匹配与多个匹配折叠成同一句话，且不指出字段，
        // 会话里无法区分"action 拼错 / 多传字段 / 取值非法"。
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
        assertTrue("须说明没有任何分支匹配", message!!.contains("不符合任何分支"))
        assertTrue("须附分支级原因并标出是哪个分支", message.contains("action=exec") || message.contains("action=close"))
        assertTrue("须指出具体字段", message.contains("action") || message.contains("command"))
        assertTrue("须给出合法取值全集", message.contains("「action」的合法取值"))
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
    fun branchFailuresNameTheBranchAndListAllAllowedValues() {
        val validator = validator(
            JSONObject(
                """
                {
                  "type": "object",
                  "properties": {
                    "action": {"type": "string", "enum": ["exec", "daemon_logs", "close", "read_async_result"]},
                    "command": {"type": "string"},
                    "task_id": {"type": "string"},
                    "session_id": {"type": "string"},
                    "job_id": {"type": "string"}
                  },
                  "required": ["action"],
                  "oneOf": [
                    {"properties": {"action": {"enum": ["exec"]}}, "required": ["action", "command"]},
                    {"properties": {"action": {"enum": ["daemon_logs"]}}, "required": ["action", "task_id"]},
                    {"properties": {"action": {"enum": ["close"]}}, "required": ["action", "session_id"]},
                    {"properties": {"action": {"enum": ["read_async_result"]}}, "required": ["action", "job_id"]}
                  ]
                }
                """.trimIndent()
            )
        )

        val message = validator.validate(call("""{"action":"open_and_exec"}"""))
        assertNotNull(message)
        // 只报"分支1/2/3"时模型无法知道在说哪个分支；必须带 action 值
        assertTrue(message!!.contains("action=exec"))
        assertTrue(message.contains("action=daemon_logs"))
        // 最该给的信息：这个参数到底能填什么
        assertTrue(message.contains("「action」的合法取值"))
        // 截断时必须说明还有多少分支没列
        assertTrue(message.contains("仅列前") || message.contains("个分支"))
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
