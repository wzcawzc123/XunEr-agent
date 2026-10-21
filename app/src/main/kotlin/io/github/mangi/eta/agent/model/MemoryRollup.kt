package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.data.repository.AgentMemoryMutation
import io.github.mangi.eta.data.repository.AgentMemoryRepository
import io.github.mangi.eta.data.repository.AgentMemoryWriteResult
import io.github.mangi.eta.data.repository.MemoryRepository
import io.github.mangi.eta.data.repository.ShortMemoryRepository
import org.json.JSONArray
import org.json.JSONObject

/**
 * 短期记忆 → 长期记忆的 rollup 整理（spec memory-upgrade §3.2，手动触发）。
 *
 * 流程（4.0 起拆两段，结果先预览再落盘）：
 * [propose] 读短期 store 全文 → LLM 提炼成去重/合并后的长期候选（Markdown 小节），
 * 只返回文本不落盘；[commit] 在用户确认后追加到长期 store。全程失败安全：
 * LLM 失败/输出无效时不写任何文件；追加走长期 store 的 revision 乐观锁
 * （先读后写，冲突则放弃本次不覆盖）。[run] 保留为两段的串行组合（测试与脚本用）。
 */
internal class MemoryRollup(
    private val config: AgentModelClient.ModelConfig,
    private val provider: AgentProviderClient,
    private val controller: AgentRunController,
    /** 可注入的仓库（默认单例；测试注入临时 store）。 */
    private val shortRepo: MemoryRepository = ShortMemoryRepository,
    private val longRepo: MemoryRepository = AgentMemoryRepository,
) {
    /** 只提炼不落盘：返回的候选文本交给用户预览确认。 */
    fun propose(): RollupDraft {
        controller.throwIfCancelled()
        val shortSnapshot = shortRepo.snapshot()
        if (shortSnapshot.content.isBlank()) {
            return RollupDraft(null, "短期记忆为空，没有可整理的内容。")
        }
        val shortContent = shortSnapshot.content.take(MAX_SHORT_INPUT_CHARS)
        val candidates = requestCandidates(shortContent)
            ?: return RollupDraft(null, "记忆整理失败：模型未返回有效结果，短期记忆未改动。")
        if (candidates.isBlank() || candidates == "(无)") {
            return RollupDraft(null, "模型判定短期记忆无需整理（无可提炼的长期事实），短期记忆未改动。")
        }
        return RollupDraft(candidates, "")
    }

    /** 用户确认后落盘：revision 乐观锁追加到长期。 */
    fun commit(candidates: String): RollupOutcome {
        controller.throwIfCancelled()
        val longSnapshot = longRepo.snapshot()
        val result = longRepo.mutate(
            AgentMemoryMutation.Append(revision = longSnapshot.revision, content = candidates),
        )
        return when (result) {
            is AgentMemoryWriteResult.Success -> {
                if (result.changed) {
                    RollupOutcome(true, "已将短期记忆提炼为长期记忆并追加。")
                } else {
                    RollupOutcome(false, "提炼结果与长期记忆现有内容逐字节一致，未产生改动。")
                }
            }
            is AgentMemoryWriteResult.Conflict -> RollupOutcome(
                false,
                "长期记忆在整理期间被修改，本次提炼未写入；请刷新后重试。",
            )
        }
    }

    fun run(): RollupOutcome {
        val draft = propose()
        val candidates = draft.candidates ?: return RollupOutcome(false, draft.message)
        return commit(candidates)
    }

    private fun requestCandidates(shortContent: String): String? {
        controller.throwIfCancelled()
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", ROLLUP_INSTRUCTION))
            .put(AgentConversationCodec.userTextMessage("待整理（短期记忆）：\n$shortContent"))
        val response = runCatching {
            provider.complete(
                ProviderRequest(config, messages, JSONArray(), purpose = ProviderRequestPurpose.COMPACTION),
                controller,
            )
        }.getOrNull() ?: return null
        controller.throwIfCancelled()
        val content = response.assistantMessage.optString("content").trim()
        if (response.stopReason != AssistantStopReason.END_TURN ||
            AgentConversationCodec.parseToolCalls(response.assistantMessage).isNotEmpty() ||
            content.isBlank() || content == "null" || content.length > MAX_CANDIDATE_CHARS
        ) {
            return null
        }
        return content
    }

    private companion object {
        const val MAX_SHORT_INPUT_CHARS = 32_000
        const val MAX_CANDIDATE_CHARS = 3_500

        const val ROLLUP_INSTRUCTION = """
你是记忆整理器。输入是"短期记忆"（当前任务/会话的临时事实），你的任务是把其中
值得长期保留的稳定事实提炼成 Markdown 小节，追加到长期记忆。

规则：
- 只输出要追加到长期记忆的 Markdown 小节（含 # 标题），不要任何解释或引言。
- 合并重复/相似条目；丢弃纯临时信息（一次性操作、已结束任务的琐碎步骤）。
- 保留精确路径、命令、错误串、标识符、用户偏好与纠正。
- 若没有值得长期保留的内容，只输出「(无)」。
- 不要重复长期记忆可能已有的通用标题（如 # 核心记忆）；用具体的小节标题。
- 输出不超过 3500 字符。
"""
    }
}

internal data class RollupOutcome(
    val ok: Boolean,
    val message: String,
)

/** [MemoryRollup.propose] 的结果：candidates 非空表示可追加的候选文本，否则 message 说明原因。 */
internal data class RollupDraft(
    val candidates: String?,
    val message: String,
)
