package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.data.repository.AgentMemoryMutation
import io.github.mangi.eta.data.repository.AgentMemoryReadResult
import io.github.mangi.eta.data.repository.AgentMemorySnapshot
import io.github.mangi.eta.data.repository.AgentMemoryWriteResult
import io.github.mangi.eta.data.repository.MemoryRepository
import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** MemoryRollup 纯逻辑测试：LLM 注入、追加长期、冲突/失败安全（内存假仓库，不碰 AtomicFile）。 */
class MemoryRollupTest {

    @Test
    fun emptyShortMemoryReturnsWithoutCallingLlm() {
        var llmCalls = 0
        val rollup = rollup(FakeMemoryRepository(""), FakeMemoryRepository("# 核心记忆\n已有"), provider = { _, _ ->
            llmCalls++
            response("不应调用 LLM")
        })
        val outcome = rollup.run()
        assertFalse(outcome.ok)
        assertEquals(0, llmCalls)
        assertTrue(outcome.message.contains("为空"))
    }

    @Test
    fun rollupAppendsLlmCandidatesToLongMemory() {
        val short = FakeMemoryRepository("# 任务\n临时想法")
        val long = FakeMemoryRepository("# 核心记忆\n已有长期")
        val rollup = rollup(short, long, provider = { _, _ -> response("# 用户偏好\n喜欢 Kotlin") })
        val outcome = rollup.run()
        assertTrue(outcome.ok)
        assertTrue(long.snapshot().content.contains("# 用户偏好"))
        assertTrue(long.snapshot().content.contains("已有长期"))
    }

    @Test
    fun blankOrNoRollupCandidateLeavesLongUntouched() {
        val short = FakeMemoryRepository("# 任务\n临时想法")
        val long = FakeMemoryRepository("# 核心记忆\n已有长期")
        val before = long.snapshot()
        val rollup = rollup(short, long, provider = { _, _ -> response("(无)") })
        val outcome = rollup.run()
        assertFalse(outcome.ok)
        assertEquals(before, long.snapshot())
    }

    @Test
    fun llmFailureLeavesBothMemoriesUntouched() {
        val short = FakeMemoryRepository("# 任务\n临时想法")
        val long = FakeMemoryRepository("# 核心记忆\n已有长期")
        val shortBefore = short.snapshot()
        val longBefore = long.snapshot()
        val rollup = rollup(short, long, provider = { _, _ ->
            throw IllegalStateException("provider down")
        })
        val outcome = rollup.run()
        assertFalse(outcome.ok)
        assertEquals(shortBefore, short.snapshot())
        assertEquals(longBefore, long.snapshot())
    }

    @Test
    fun conflictOnLongDoesNotOverwriteConcurrentWrite() {
        val short = FakeMemoryRepository("# 任务\n临时想法")
        val long = FakeMemoryRepository("# 核心记忆\nA")
        val staleRevision = long.snapshot().revision
        // 并发写入改变 revision。
        val concurrent = long.mutate(AgentMemoryMutation.Append(staleRevision, "# 并发写入\nB"))
        assertTrue(concurrent is AgentMemoryWriteResult.Success)
        // 用过期 revision 追加（模拟 rollup 拿到旧 revision 后长期被并发改）→ 冲突。
        val stale = long.mutate(AgentMemoryMutation.Append(staleRevision, "# 过期追加\nC"))
        assertTrue(stale is AgentMemoryWriteResult.Conflict)
        assertTrue(long.snapshot().content.contains("# 并发写入"))
        assertFalse(long.snapshot().content.contains("# 过期追加"))
    }

    private fun rollup(
        short: MemoryRepository,
        long: MemoryRepository,
        provider: (ProviderRequest, (ProviderEvent) -> Unit) -> ProviderResponse,
    ): MemoryRollup = MemoryRollup(
        config = AgentModelClient.ModelConfig(baseUrl = "https://fixture", apiKey = "key", model = "fixture", systemPrompt = ""),
        provider = object : AgentProviderClient {
            override val id = "fixture"
            override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, true, true, false, false)
            override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit): ProviderResponse =
                provider(request, onEvent)
        },
        controller = AgentRunController(),
        shortRepo = short,
        longRepo = long,
    )

    private fun response(text: String) = ProviderResponse(
        JSONObject().put("role", "assistant").put("content", text).put("finish_reason", "stop"),
    )
}

/** 内存版 MemoryRepository：模拟快照/追加/revision 乐观锁，不依赖 Android AtomicFile。 */
private class FakeMemoryRepository(initial: String) : MemoryRepository {
    private var content = initial

    override fun snapshot(): AgentMemorySnapshot = AgentMemorySnapshot(
        content = content,
        revision = sha256(content),
        byteSize = content.toByteArray(Charsets.UTF_8).size,
        lineCount = content.split('\n').let { if (content.isEmpty()) 0 else it.size },
    )

    override fun read(query: String?, startLine: Int, maxChars: Int): AgentMemoryReadResult =
        AgentMemoryReadResult(snapshot(), "", null, null, false, 0)

    override fun readSection(section: String, maxChars: Int): AgentMemoryReadResult =
        AgentMemoryReadResult(snapshot(), "", null, null, false, 0, section)

    override fun mutate(mutation: AgentMemoryMutation): AgentMemoryWriteResult {
        if (mutation.revision != snapshot().revision) return AgentMemoryWriteResult.Conflict(snapshot())
        val updated = when (mutation) {
            is AgentMemoryMutation.ReplaceRange -> mutation.content
            is AgentMemoryMutation.ReplaceSection -> mutation.content
            is AgentMemoryMutation.Append ->
                if (content.isEmpty()) mutation.content else content.trimEnd('\n') + "\n" + mutation.content
            is AgentMemoryMutation.Clear -> ""
        }
        val changed = updated != content
        content = updated
        return AgentMemoryWriteResult.Success(snapshot(), changed)
    }

    override fun replaceAll(content: String): AgentMemorySnapshot {
        this.content = content
        return snapshot()
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
}
