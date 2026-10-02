package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
