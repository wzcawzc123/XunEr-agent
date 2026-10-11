package io.github.mangi.eta.ui.app

import android.content.Context
import io.github.mangi.eta.agent.model.AgentConversationCodec
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.roleplay.CharacterCardCodec
import io.github.mangi.eta.agent.roleplay.RoleplayBinding
import io.github.mangi.eta.agent.roleplay.RoleplayMessageLink
import io.github.mangi.eta.agent.roleplay.RoleplayMessageState
import io.github.mangi.eta.data.db.ConversationEntity
import io.github.mangi.eta.data.db.ConversationMessageEntity
import io.github.mangi.eta.data.db.ConversationStateEntity
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.ui.model.AgentChatHomeUiState
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.SystemNoticeCode
import io.github.mangi.eta.ui.model.SystemNoticeMessageUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.TokenUsageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.ToolActivityStatusUi
import io.github.mangi.eta.ui.model.UserMessageUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS")
class AgentConversationStoreTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
    }

    @Test
    fun repeatedSavePreservesRoleBindingRevisionsPendingRewriteAndOriginalJournal() = runBlocking {
        val original = AgentModelClient.ConversationMessage(
            role = "assistant", content = "原始回答", messageId = "assistant-role-1",
        )
        val binding = RoleplayBinding(
            characterId = "character-1",
            cardSnapshotJson = CharacterCardCodec.encodeJson(CharacterCardCodec.create("旅人")),
            characterName = "旅人", userName = "朋友", userDescription = "同行的伙伴",
        )
        val state = AgentChatHomeUiState(
            messages = listOf(AgentMessageUi(id = original.messageId, content = original.content, isStreaming = false)),
            input = "", isStreaming = false, thinkingEnabled = false,
            journal = listOf(original), history = listOf(original), roleplay = binding,
            roleplayMessages = RoleplayMessageState(
                links = mapOf(original.messageId to RoleplayMessageLink(original.messageId)),
                pendingRewrites = mapOf("rewrite-in-flight" to original.messageId),
            ),
        )
        var role = RoleplayConversationReducer.edit(state, original.messageId, "用户修订的回答")!!
        repeat(2) {
            AgentConversationStore.save(
                context, "role", mapOf("role" to role, "ordinary" to AgentChatHomeUiState(
                    messages = listOf(UserMessageUi(id = "ordinary-user", content = "查看电量")),
                    input = "", isStreaming = false, thinkingEnabled = false,
                )), mapOf("role" to "旅人", "ordinary" to "查看电量"), mapOf("role" to 1L, "ordinary" to 2L),
            )
            val restored = AgentConversationStore.load(context)
            role = restored.conversationsById.getValue("role")
            assertEquals(binding, role.roleplay)
            assertEquals(listOf(original), role.journal)
            assertEquals("用户修订的回答", role.history.single().content)
            assertEquals("rewrite-in-flight", role.roleplayMessages.pendingRewrites.keys.single())
            assertEquals(listOf("原始回答", "用户修订的回答"), role.roleplayMessages.revisions.getValue(original.messageId).candidates)
            assertEquals(2, (role.messages.single() as AgentMessageUi).candidateCount)
            assertEquals(null, restored.conversationsById.getValue("ordinary").roleplay)
            assertTrue(restored.conversationsById.getValue("ordinary").roleplayMessages.revisions.isEmpty())
        }
        val switched = RoleplayConversationReducer.select(role, original.messageId, 0)!!
        assertEquals("原始回答", switched.history.single().content)
        assertEquals(listOf(original), switched.journal)
    }

    @Test
    fun saveAndLoadPreservesConversations() {
        val conversation = AgentChatHomeUiState(
            messages = listOf(
                UserMessageUi(
                    id = "user-1",
                    content = "看一下当前屏幕",
                    isEdited = true,
                ),
                ThinkingMessageUi(
                    id = "thinking-1",
                    content = "需要先观察屏幕",
                    isStreaming = false,
                    elapsedSeconds = 3,
                    collapsed = true,
                ),
                ToolActivityMessageUi(
                    id = "tool-1",
                    toolName = "run_command",
                    status = ToolActivityStatusUi.Success,
                    argumentsSummary = "执行命令 · Android · root",
                    command = "pm list packages | head",
                    resultSummary = "ok=true, chars=100",
                    imageCount = 1,
                ),
                AgentMessageUi(
                    id = "assistant-1",
                    content = "| 项目 | 内容 |\n| --- | --- |\n| 电量 | 88% |",
                    isStreaming = false,
                    renderMarkdown = true,
                    usage = TokenUsageUi(
                        contextTokens = 100,
                        inputTokens = 30,
                        outputTokens = 40,
                        reasoningTokens = 20,
                        cachedTokens = 10,
                    ),
                ),
            ),
            history = listOf(
                io.github.mangi.eta.agent.model.AgentModelClient.ConversationMessage(
                    role = "user",
                    content = "看一下当前屏幕",
                ),
                io.github.mangi.eta.agent.model.AgentModelClient.ConversationMessage(
                    role = "assistant",
                    content = "",
                    reasoningContent = "需要先观察屏幕",
                    toolCallsJson = """[{"id":"toolu_1","type":"function","function":{"name":"observe_screen","arguments":"{}"}}]""",
                ),
                io.github.mangi.eta.agent.model.AgentModelClient.ConversationMessage(
                    role = "tool",
                    content = "{\"ok\":true}",
                    toolCallId = "toolu_1",
                ),
                io.github.mangi.eta.agent.model.AgentModelClient.ConversationMessage(
                    role = "assistant",
                    content = "| 项目 | 内容 |\n| --- | --- |\n| 电量 | 88% |",
                ),
            ),
            input = "不应该保存草稿",
            isStreaming = true,
            thinkingEnabled = true,
            reasoningEffort = ReasoningEffort.HIGH,
        )

        runBlocking {
            AgentConversationStore.save(
                context = context,
                selectedConversationId = "conv-1",
                conversationsById = mapOf("conv-1" to conversation),
                titles = mapOf("conv-1" to "屏幕分析"),
                updatedAt = mapOf("conv-1" to 1234L),
            )
        }

        val snapshot = AgentConversationStore.load(context)

        assertEquals("conv-1", snapshot.selectedConversationId)
        assertEquals("屏幕分析", snapshot.titles.getValue("conv-1"))
        assertEquals(1234L, snapshot.updatedAt.getValue("conv-1"))
        val restored = snapshot.conversationsById.getValue("conv-1")
        assertEquals("", restored.input)
        assertFalse(restored.isStreaming)
        assertTrue(restored.thinkingEnabled)
        assertEquals(ReasoningEffort.HIGH, restored.reasoningEffort)
        assertEquals(conversation.messages, restored.messages)
        assertEquals(conversation.history, restored.history)
    }

    @Test
    fun saveAndLoadPreservesSemanticSystemNoticesWithoutTranslatedContent() {
        val notice = SystemNoticeMessageUi(
            id = "assistant-run-1-1",
            code = SystemNoticeCode.RuntimeFailed,
            detail = "upstream timeout",
        )
        runBlocking {
            AgentConversationStore.save(
                context = context,
                selectedConversationId = "conv-notice",
                conversationsById = mapOf(
                    "conv-notice" to AgentChatHomeUiState(
                        messages = listOf(notice),
                        input = "",
                        isStreaming = false,
                        thinkingEnabled = false,
                    ),
                ),
                titles = mapOf("conv-notice" to ""),
                updatedAt = mapOf("conv-notice" to 1L),
            )
        }

        val snapshot = AgentConversationStore.load(context)
        assertEquals("", snapshot.titles.getValue("conv-notice"))
        assertEquals(
            notice,
            snapshot.conversationsById.getValue("conv-notice").messages.single(),
        )
    }

    @Test
    fun conversationModelBindingRoundTripsAndChangeAloneIsPersisted() = runBlocking {
        val user = UserMessageUi(id = "user-1", content = "你好")
        val base = AgentChatHomeUiState(
            messages = listOf(user), input = "", isStreaming = false, thinkingEnabled = false,
            reasoningEffort = ReasoningEffort.OFF,
        )
        val persistence = AgentConversationPersistence()
        fun snapshot(state: AgentChatHomeUiState) = AgentConversationStore.Snapshot(
            selectedConversationId = "conv-a",
            conversationsById = mapOf("conv-a" to state, "conv-b" to base),
            titles = mapOf("conv-a" to "A", "conv-b" to "B"),
            updatedAt = mapOf("conv-a" to 1L, "conv-b" to 1L),
        )
        persistence.save(context, snapshot(base.copy(modelId = "model-x")))
        assertEquals("model-x", AgentConversationStore.load(context).conversationsById.getValue("conv-a").modelId)

        persistence.save(context, snapshot(base.copy(modelId = "model-y")))
        val restored = AgentConversationStore.load(context).conversationsById
        assertEquals("model-y", restored.getValue("conv-a").modelId)
        assertEquals(null, restored.getValue("conv-b").modelId)
    }

    @Test
    fun unknownStoredEffortFallsBackToDefault() {
        runBlocking {
            EtaDatabase.get(context).conversationDao().replaceAll(
                conversations = listOf(
                    ConversationEntity(
                        id = "conv-unknown",
                        title = "Unknown",
                        thinkingEnabled = false,
                        reasoningEffort = "future_effort",
                        createdAt = 1L,
                        updatedAt = 1L,
                    )
                ),
                messages = emptyList(),
                state = ConversationStateEntity(selectedConversationId = "conv-unknown"),
            )
        }

        val restored = AgentConversationStore.load(context)
            .conversationsById
            .getValue("conv-unknown")

        assertEquals(ReasoningEffort.DEFAULT, restored.reasoningEffort)
        assertTrue(restored.thinkingEnabled)
    }

    @Test
    fun saveAndLoadPreservesAllConversationsAndMessagesWithoutClipping() {
        val longContent = "x".repeat(20_000)
        val primaryMessages = buildList {
            add(UserMessageUi(id = "conv-0-user-long", content = longContent))
            repeat(130) { index ->
                add(
                    AgentMessageUi(
                        id = "conv-0-assistant-$index",
                        content = "assistant-$index",
                        isStreaming = false,
                    )
                )
            }
        }
        val conversations = buildMap {
            put(
                "conv-0",
                AgentChatHomeUiState(
                    messages = primaryMessages,
                    input = "",
                    isStreaming = false,
                    thinkingEnabled = false,
                )
            )
            repeat(59) { index ->
                val id = "conv-${index + 1}"
                put(
                    id,
                    AgentChatHomeUiState(
                        messages = listOf(UserMessageUi(id = "$id-user", content = "message-$id")),
                        input = "",
                        isStreaming = false,
                        thinkingEnabled = false,
                    )
                )
            }
        }
        val titles = conversations.keys.associateWith { id -> "title-$id" }
        val updatedAt = conversations.keys.associateWith { id -> id.removePrefix("conv-").toLong() }

        runBlocking {
            AgentConversationStore.save(
                context = context,
                selectedConversationId = "conv-0",
                conversationsById = conversations,
                titles = titles,
                updatedAt = updatedAt,
            )
        }

        val snapshot = AgentConversationStore.load(context)

        assertEquals(60, snapshot.conversationsById.size)
        val restored = snapshot.conversationsById.getValue("conv-0")
        assertEquals(131, restored.messages.size)
        assertEquals(longContent, (restored.messages.first() as UserMessageUi).content)
        assertEquals("assistant-129", (restored.messages.last() as AgentMessageUi).content)
    }

    @Test
    fun savePreservesCompleteContextAndDisplayedMessages() {
        val displayedContent = "展示消息-${"d".repeat(120_000)}"
        val history = buildList {
            repeat(20) { index ->
                add(
                    AgentModelClient.ConversationMessage(
                        role = "assistant",
                        content = "历史-$index-${"h".repeat(20_000)}",
                    )
                )
            }
            add(AgentModelClient.ConversationMessage(role = "user", content = "最新上下文"))
        }

        runBlocking {
            AgentConversationStore.save(
                context = context,
                selectedConversationId = "conv-large",
                conversationsById = mapOf(
                    "conv-large" to AgentChatHomeUiState(
                        messages = listOf(
                            UserMessageUi(id = "user-large", content = displayedContent)
                        ),
                        history = history,
                        input = "",
                        isStreaming = false,
                        thinkingEnabled = false,
                    )
                ),
                titles = mapOf("conv-large" to "长对话"),
                updatedAt = mapOf("conv-large" to 1L),
            )
        }

        val checkpoint = runBlocking {
            EtaDatabase.get(context)
                .conversationDao()
                .contextCheckpoint("conv-large")!!
        }
        val restored = AgentConversationStore.load(context)
            .conversationsById
            .getValue("conv-large")

        assertTrue(checkpoint.historyJson.length > 96_000)
        assertEquals(history, restored.history)
        assertEquals(history, restored.journal)
        assertEquals(displayedContent, (restored.messages.single() as UserMessageUi).content)
        assertEquals("最新上下文", restored.history.last().content)
    }

    @Test
    fun loadIgnoresLegacyHistoryColumnAndFallsBackToMessageRows() {
        runBlocking {
            val dao = EtaDatabase.get(context).conversationDao()
            dao.insertConversations(
                listOf(
                    ConversationEntity(
                        id = "conv-legacy-large",
                        title = "旧长对话",
                        thinkingEnabled = false,
                        historyJson = "x".repeat(2_500_000),
                        createdAt = 1L,
                        updatedAt = 1L,
                    )
                )
            )
            dao.insertMessages(
                listOf(
                    ConversationMessageEntity(
                        id = "legacy-user",
                        conversationId = "conv-legacy-large",
                        sortIndex = 0,
                        type = "user",
                        content = "从消息记录恢复",
                    )
                )
            )
        }

        val restored = AgentConversationStore.load(context)
            .conversationsById
            .getValue("conv-legacy-large")

        assertEquals("从消息记录恢复", restored.history.single().content)
        assertEquals("从消息记录恢复", (restored.messages.single() as UserMessageUi).content)
    }

    @Test
    fun loadKeepsDatabaseEmptyUntilFirstMessageIsSent() {
        val snapshot = AgentConversationStore.load(context)

        assertTrue(snapshot.conversationsById.isEmpty())
        assertEquals(null, snapshot.selectedConversationId)
    }

    @Test
    fun characterGreetingIsLocalAndOrdinaryNewConversationReturnsToEta() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val state = AgentAppState(context, scope)
            val binding = RoleplayBinding(
                "local-character", CharacterCardCodec.encodeJson(CharacterCardCodec.create("旅人")),
                "旅人", userName = "小林",
            )
            state.startCharacterConversation(binding, "你好，{{user}}，我是{{char}}。")
            assertFalse(state.homeState.isStreaming)
            assertEquals("你好，小林，我是旅人。", (state.homeState.messages.single() as AgentMessageUi).content)
            assertEquals(binding, state.homeState.roleplay)
            assertTrue(state.homeState.appliedRuntimeRunIds.isEmpty())
            assertTrue(state.homeState.roleplayMessages.pendingRewrites.isEmpty())

            state.createConversation()
            assertEquals(null, state.homeState.roleplay)
            assertTrue(state.homeState.history.isEmpty())
            assertTrue(state.homeState.messages.isEmpty())
            assertFalse(state.homeState.isStreaming)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun creatingConversationKeepsEmptyStateOutOfHistoryAndDatabase() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val state = AgentAppState(context, scope)

            state.createConversation()
            state.createConversation()

            assertEquals(null, state.conversationPaneState.selectedConversationId)
            assertTrue(state.conversationPaneState.conversations.isEmpty())
            assertTrue(
                runBlocking {
                    EtaDatabase.get(context).conversationDao().conversations().isEmpty()
                }
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun loadSnapshotLoadsOnlySelectedConversationMessages() = runBlocking {
        val selectedState = AgentChatHomeUiState(
            messages = listOf(UserMessageUi("selected-user", "选中会话")),
            history = listOf(
                io.github.mangi.eta.agent.model.AgentModelClient.ConversationMessage(role = "user", content = "选中会话")
            ),
            journal = listOf(
                io.github.mangi.eta.agent.model.AgentModelClient.ConversationMessage(role = "user", content = "选中会话")
            ),
            input = "", isStreaming = false, thinkingEnabled = false,
        )
        val otherState = AgentChatHomeUiState(
            messages = listOf(UserMessageUi("other-user", "其他会话")),
            input = "", isStreaming = false, thinkingEnabled = false,
        )
        AgentConversationStore.save(
            context = context,
            selectedConversationId = "conv-selected",
            conversationsById = mapOf("conv-selected" to selectedState, "conv-other" to otherState),
            titles = mapOf("conv-selected" to "选中", "conv-other" to "其他"),
            updatedAt = mapOf("conv-selected" to 2L, "conv-other" to 1L),
        )

        val snapshot = AgentConversationStore.load(context)

        assertEquals("conv-selected", snapshot.selectedConversationId)
        val selected = snapshot.conversationsById.getValue("conv-selected")
        assertTrue(selected.messagesLoaded)
        assertEquals(listOf(UserMessageUi("selected-user", "选中会话")), selected.messages)
        assertEquals(
            listOf(io.github.mangi.eta.agent.model.AgentModelClient.ConversationMessage(role = "user", content = "选中会话")),
            selected.history,
        )
        val other = snapshot.conversationsById.getValue("conv-other")
        assertFalse(other.messagesLoaded)
        assertTrue(other.messages.isEmpty())
        assertTrue(other.history.isEmpty())
        assertTrue(other.journal.isEmpty())
        assertEquals("其他", snapshot.titles.getValue("conv-other"))
        assertEquals(UserMessageUi("other-user", "其他会话"), snapshot.previews.getValue("conv-other"))
    }

    @Test
    fun savingWhileConversationUnloadedDoesNotDeleteStoredMessages() = runBlocking {
        val a = AgentChatHomeUiState(
            messages = listOf(UserMessageUi("a-user", "A 会话")),
            history = listOf(io.github.mangi.eta.agent.model.AgentModelClient.ConversationMessage(role = "user", content = "A 会话")),
            journal = listOf(io.github.mangi.eta.agent.model.AgentModelClient.ConversationMessage(role = "user", content = "A 会话")),
            input = "", isStreaming = false, thinkingEnabled = false,
        )
        val b = AgentChatHomeUiState(
            messages = listOf(UserMessageUi("b-user", "B 消息一"), UserMessageUi("b-user-2", "B 消息二")),
            input = "", isStreaming = false, thinkingEnabled = false,
        )
        AgentConversationStore.save(
            context = context,
            selectedConversationId = "conv-a",
            conversationsById = mapOf("conv-a" to a, "conv-b" to b),
            titles = mapOf("conv-a" to "A", "conv-b" to "B"),
            updatedAt = mapOf("conv-a" to 2L, "conv-b" to 1L),
        )

        // 重启：选中 A，B 未载入（messagesLoaded=false）
        val snapshot = AgentConversationStore.load(context)
        val unloadedB = snapshot.conversationsById.getValue("conv-b")
        assertFalse(unloadedB.messagesLoaded)

        // 以该快照重建持久化（模拟重启后的保存路径），保存一次：B 的消息区必须原样保留
        val persistence = AgentConversationPersistence(snapshot)
        persistence.save(context, snapshot.copy(titles = snapshot.titles + ("conv-b" to "改名后的 B")))

        assertEquals(
            listOf(UserMessageUi("b-user", "B 消息一"), UserMessageUi("b-user-2", "B 消息二")),
            AgentConversationStore.loadConversation(context, "conv-b")!!.messages,
        )
        assertEquals("改名后的 B", AgentConversationStore.load(context).titles.getValue("conv-b"))
    }

    @Test
    fun lazyLoadConversationRestoresFullMessagesAndCheckpoint() = runBlocking {
        val history = listOf(io.github.mangi.eta.agent.model.AgentModelClient.ConversationMessage(role = "assistant", content = "历史"))
        val state = AgentChatHomeUiState(
            messages = listOf(UserMessageUi("user-1", "请求"), AgentMessageUi("assistant-1", "回答")),
            history = history, journal = history, input = "", isStreaming = false, thinkingEnabled = true,
        )
        AgentConversationStore.save(
            context = context,
            selectedConversationId = "conv-lazy",
            conversationsById = mapOf("conv-lazy" to state),
            titles = mapOf("conv-lazy" to "懒加载"),
            updatedAt = mapOf("conv-lazy" to 1L),
        )
        AgentConversationStore.load(context)

        val restored = AgentConversationStore.loadConversation(context, "conv-lazy")!!

        assertTrue(restored.messagesLoaded)
        assertEquals(
            listOf(UserMessageUi("user-1", "请求"), AgentMessageUi("assistant-1", "回答")),
            restored.messages,
        )
        assertEquals(history, restored.history)
        assertEquals(history, restored.journal)
    }

    @Test
    fun savingEmptySnapshotClearsPreviouslyPersistedConversations() {
        runBlocking {
            AgentConversationStore.save(
                context = context,
                selectedConversationId = "conv-1",
                conversationsById = mapOf(
                    "conv-1" to AgentChatHomeUiState(
                        messages = listOf(UserMessageUi(id = "user-1", content = "hello")),
                        input = "",
                        isStreaming = false,
                        thinkingEnabled = false,
                    )
                ),
                titles = mapOf("conv-1" to "hello"),
                updatedAt = mapOf("conv-1" to 1L),
            )
            AgentConversationStore.save(
                context = context,
                selectedConversationId = null,
                conversationsById = emptyMap(),
                titles = emptyMap(),
                updatedAt = emptyMap(),
            )
        }

        val snapshot = AgentConversationStore.load(context)
        assertTrue(snapshot.conversationsById.isEmpty())
        assertEquals(null, snapshot.selectedConversationId)
    }
}
