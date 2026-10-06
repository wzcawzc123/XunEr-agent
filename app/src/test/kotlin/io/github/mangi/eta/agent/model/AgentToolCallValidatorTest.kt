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
        assertTrue("须附分支级原因", message.contains("分支1"))
        assertTrue("须指出具体字段", message.contains("action") || message.contains("command"))
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
