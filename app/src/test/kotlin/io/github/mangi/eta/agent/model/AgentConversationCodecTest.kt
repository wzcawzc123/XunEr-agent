package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentLegacyConversationProjection
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentConversationCodecTest {
    @Test
    fun toolRoundTripPreservesReasoningContentForCompatibleProviders() {
        val assistant = JSONObject()
            .put("role", "assistant")
            .put("content", JSONObject.NULL)
            .put("reasoning_content", "先分析工具参数")
            .put(
                "tool_calls",
                JSONArray().put(
                    JSONObject()
                        .put("id", "call-1")
                        .put("type", "function")
                        .put(
                            "function",
                            JSONObject()
                                .put("name", "device_info")
                                .put("arguments", "{}")
                        )
                )
            )

        val durable = AgentConversationCodec.durableMessage(assistant)
        val replayed = AgentConversationCodec.toJsonObject(durable)

        assertEquals("先分析工具参数", replayed.getString("reasoning_content"))
        assertEquals("call-1", replayed.getJSONArray("tool_calls").getJSONObject(0).getString("id"))
    }

    @Test
    fun durableImageObservationNeverPersistsBase64Payload() {
        val message = AgentConversationCodec.durableMessage(
            AgentConversationCodec.userMessage(
                text = "屏幕观察",
                images = listOf(
                    AgentModelClient.ModelImage(
                        reference = "data:image/png;base64,${"A".repeat(20_000)}",
                        mimeType = "image/png",
                        bytes = 15_000,
                    )
                ),
            )
        )

        assertFalse(message.contentJson.contains("base64"))
        assertTrue(message.contentJson.contains("未写入持久会话"))
    }

    @Test
    fun ipcTranscriptHasHardBudgetAndNeverStartsWithOrphanToolResult() {
        val messages = buildList {
            repeat(20) { index ->
                add(
                    AgentModelClient.ConversationMessage(
                        role = "assistant",
                        content = "回答-$index-${"x".repeat(20_000)}",
                    )
                )
                add(
                    AgentModelClient.ConversationMessage(
                        role = "tool",
                        toolCallId = "call-$index",
                        content = "结果-${"y".repeat(20_000)}",
                    )
                )
            }
            add(AgentModelClient.ConversationMessage(role = "assistant", content = "最终答案"))
        }

        val encoded = AgentLegacyConversationProjection.encode(messages, AgentLegacyConversationProjection.DIRECT_CHARS)
        val decoded = AgentConversationCodec.decodeTranscript(encoded)

        assertTrue(encoded.length <= AgentLegacyConversationProjection.DIRECT_CHARS)
        assertTrue(decoded.isNotEmpty())
        assertFalse(decoded.first().role == "tool")
        assertTrue(decoded.first().content.contains("容量上限已压缩"))
        assertTrue(decoded.last().content.contains("最终答案"))
    }

    @Test
    fun conversationCheckpointPreservesEveryMessageBeyondLegacyBudget() {
        val messages = buildList {
            repeat(20) { index ->
                add(
                    AgentModelClient.ConversationMessage(
                        role = "assistant",
                        content = "回答-$index-${"x".repeat(20_000)}",
                    )
                )
            }
            add(AgentModelClient.ConversationMessage(role = "user", content = "继续处理最新任务"))
        }

        val encoded = AgentConversationCodec.encodeConversationCheckpoint(messages)
        val decoded = AgentConversationCodec.decodeTranscript(encoded)

        assertTrue(encoded.length > 96_000)
        assertEquals(messages, decoded)
        assertEquals("继续处理最新任务", decoded.last().content)
    }

    @Test
    fun responsesOutputItemsStayInMemoryAndNeverEnterStableTranscript() {
        val source = JSONObject().put("role", "assistant").put("content", "完成")
        ResponsesEphemeralState.attachOutputItems(
            source,
            JSONArray().put(
                JSONObject()
                    .put("type", "reasoning")
                    .put("encrypted_content", "opaque-secret"),
            ),
        )
        val history = AgentConversationCodec.assistantHistoryMessage(source, emptyList())
        assertTrue(ResponsesEphemeralState.outputItems(history) != null)

        val stable = AgentConversationCodec.durableMessage(history)
        val encoded = AgentConversationCodec.encodeTranscriptForStorage(listOf(stable))
        assertFalse(encoded.contains("opaque-secret"))
        assertFalse(encoded.contains("_eta_responses_output_items"))
    }

    @Test
    fun stripImagesRemovesChatImageUrlBlocksAndReturnsNewArray() {
        val message = JSONObject()
            .put("role", "user")
            .put(
                "content",
                JSONArray()
                    .put(JSONObject().put("type", "text").put("text", "看这张图"))
                    .put(
                        JSONObject()
                            .put("type", "image_url")
                            .put("image_url", JSONObject().put("url", "data:image/png;base64,AA=="))
                    )
            )
        val messages = JSONArray().put(message)

        val stripped = AgentConversationCodec.stripImagesForTextOnlyModel(messages)

        assertTrue(stripped !== messages)
        assertEquals(1, stripped.length())
        val content = stripped.getJSONObject(0).getJSONArray("content")
        assertEquals(1, content.length())
        assertEquals("text", content.getJSONObject(0).getString("type"))
        // 有文本的消息只删图片块，不追加占位符。
        assertEquals("看这张图", content.getJSONObject(0).getString("text"))
        // 原数组不被污染（持久历史保持原样）。
        assertTrue(messages.getJSONObject(0).getJSONArray("content").length() == 2)
    }

    @Test
    fun stripImagesRemovesResponsesInputImageBlocks() {
        val message = JSONObject()
            .put("role", "user")
            .put(
                "content",
                JSONArray().put(
                    JSONObject()
                        .put("type", "input_image")
                        .put("image_url", "data:image/png;base64,AA==")
                )
            )
        val stripped = AgentConversationCodec.stripImagesForTextOnlyModel(JSONArray().put(message))

        assertEquals(1, stripped.length())
        val content = stripped.getJSONObject(0).getJSONArray("content")
        // 纯图片消息保留占位符文本，保证请求合法。
        assertEquals(1, content.length())
        assertEquals("text", content.getJSONObject(0).getString("type"))
        assertTrue(content.getJSONObject(0).getString("text").contains("图片已忽略"))
    }

    @Test
    fun stripImagesRemovesAnthropicImageSourceBlocks() {
        val message = JSONObject()
            .put("role", "user")
            .put(
                "content",
                JSONArray().put(
                    JSONObject()
                        .put("type", "image")
                        .put(
                            "source",
                            JSONObject()
                                .put("type", "base64")
                                .put("media_type", "image/png")
                                .put("data", "AA==")
                        )
                )
            )
        val stripped = AgentConversationCodec.stripImagesForTextOnlyModel(JSONArray().put(message))

        assertEquals(1, stripped.length())
        val content = stripped.getJSONObject(0).getJSONArray("content")
        assertEquals(1, content.length())
        assertTrue(content.getJSONObject(0).getString("text").contains("图片已忽略"))
    }

    @Test
    fun stripImagesReturnsOriginalReferenceWhenNothingToStrip() {
        val message = JSONObject()
            .put("role", "user")
            .put("content", "纯文本消息")
        val messages = JSONArray().put(message)

        val stripped = AgentConversationCodec.stripImagesForTextOnlyModel(messages)

        assertSame(messages, stripped)
    }

    @Test
    fun isImageBlockCoversAllThreeProviderFormats() {
        assertTrue(
            AgentConversationCodec.isImageBlock(
                JSONObject().put("type", "image_url").put("image_url", JSONObject())
            )
        )
        assertTrue(
            AgentConversationCodec.isImageBlock(
                JSONObject().put("type", "input_image")
            )
        )
        assertTrue(
            AgentConversationCodec.isImageBlock(
                JSONObject().put("type", "image").put("source", JSONObject())
            )
        )
        assertFalse(
            AgentConversationCodec.isImageBlock(
                JSONObject().put("type", "text").put("text", "普通文本")
            )
        )
        assertFalse(
            AgentConversationCodec.isImageBlock(
                JSONObject().put("type", "tool_result").put("content", "结果")
            )
        )
    }
}
