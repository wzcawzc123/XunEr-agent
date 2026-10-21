package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunController
import org.json.JSONArray

/** 管理可替换的模型上下文；持久快照先提交，运行 transcript 始终追加。 */
internal class AgentContextSession(
    private val config: AgentModelClient.ModelConfig,
    private val messages: JSONArray,
    private val systemCount: Int,
    private val operationId: String,
    private val provider: AgentProviderClient,
    private val runController: AgentRunController,
    private val sensitiveIds: () -> Set<String>,
    private val onEvent: (AgentEvent) -> Unit,
    private val onContextSnapshot: (AgentContextSnapshot) -> Unit,
    private val transcriptSize: () -> Int = { 0 },
    private val roleplay: Boolean = false,
    /**
     * 压缩时读取当前任务清单概览（一行文本）。
     *
     * 用回调而不是直接依赖 DAO：Session 属于 model 层，不应知道存储细节；
     * 由运行时层（持有 conversationId 与数据库）注入实现，缺失时返回 null 即可。
     */
    private val planOverview: () -> String? = { null },
) {
    private val contextWindow = config.knownContextWindow
    private var inputTokens: Int? = null
    private var compacted = false
    /** C3b：连续未收到 usage 的轮数（服务商不回 input_tokens 时自动压缩停摆的遥测）。 */
    private var missingUsageRounds = 0

    /**
     * 压缩失败熔断（只在本次 run 内生效）。
     *
     * 真机教训（2026-10-07，会话 conv-3d2f1ce4）：失败分支既不重置 [inputTokens] 也不记状态，
     * 而每轮 loop 都会调用 [compact]，于是额度不足时退化成「每轮再压一次」的风暴——
     * 每次压缩都是一次全量摘要请求（该会话单轮 input 达 75 万 tokens）并按全价击穿缓存，
     * 表现为上下文压缩事件连续出现、额度瞬间耗尽（HTTP 402）。失败一次即停，
     * 兜底交给窗口级硬裁剪，跨 run 自动恢复。
     */
    private var compactionBlocked = false
    private var consumedSupplementCount = 0
    private var consumedUserTurns = (systemCount until messages.length()).sumOf {
        val message = messages.getJSONObject(it)
        message.optInt("_eta_compacted_users") + if (message.optString("role") == "user") 1 else 0
    }
    private val budget by lazy { AgentContextBudget(contextWindow) }
    private var committedSnapshot: AgentContextSnapshot? = null

    fun snapshot(): AgentContextSnapshot? = committedSnapshot

    /**
     * 本次 run 是否已因压缩失败而熔断。
     *
     * 熔断后不能再指望摘要降规模，调用方（[AgentLoop]）应把硬裁剪线降到触发线——
     * 否则每轮仍会把整份超线上下文发出去，只是把「重复压缩」换成了「重复超线请求」。
     * （spec：docs/specs/context-cost-guard.md R6）
     */
    val compactionBlockedForRun: Boolean get() = compactionBlocked

    fun observeInputTokens(tokens: Int?) {
        inputTokens = tokens?.takeIf { it >= 0 }
        // C3b（v3.11.26）：连续多轮未收到 usage（服务商不回 input_tokens）→ 发诊断事件，
        // 暴露"自动压缩停摆"（inputTokens 恒 null，触发线不可达，只剩硬裁剪兜底）。
        // 只在达到阈值后触发一次，避免每轮刷屏。
        if (inputTokens == null) {
            missingUsageRounds++
            if (missingUsageRounds == MISSING_USAGE_EVENT_THRESHOLD) {
                onEvent(AgentEvent.CompactionUnavailable(operationId, missingUsageRounds))
            }
        } else {
            missingUsageRounds = 0
        }
    }

    /** 用实测校准本地估算；AgentLoop 在收到 usage 后调用。 */
    fun observeBudget(inputTokens: Int?, requestEstimate: Int) = budget.observeTokens(inputTokens, requestEstimate)

    /** 当前校准系数；硬裁剪目标换算需要与判据同一口径。 */
    fun calibrationValue(): Double = budget.calibrationValue()

    /** 校准后的上下文估算；裁剪判据用它而不是未校准的 rawEstimate。 */
    fun budgetEstimate(messages: JSONArray, tools: JSONArray = JSONArray()): Int =
        budget.estimate(messages, tools)

    fun userAppended() {
        consumedUserTurns++
        consumedSupplementCount++
    }

    private fun publishSnapshot(candidate: JSONArray = messages) {
        if (!compacted) return
        val snapshot = createSnapshot(candidate)
        snapshot.encode()
        onContextSnapshot(snapshot)
        committedSnapshot = snapshot
    }

    private fun createSnapshot(candidate: JSONArray): AgentContextSnapshot {
        val history = durableHistory(candidate)
        return AgentContextSnapshot(
            operationId = operationId,
            messages = history,
            coveredUserTurns = history.sumOf { it.compactedUserTurns },
            consumedUserTurns = consumedUserTurns,
            consumedSupplementCount = consumedSupplementCount,
            consumedTranscriptMessages = transcriptSize(),
        )
    }

    fun compact(force: Boolean = false, final: Boolean = false) {
        val before = inputTokens
        val trigger = AgentContextBudget.triggerTokens(contextWindow)
        if (AnthropicEphemeralState.hasPendingToolResponse(messages)) {
            if (force) {
                throw AgentContextCompactor.signedAnthropicToolRoundFailure()
            }
            return
        }
        if (!force && (compactionBlocked || !config.autoCompactionEnabled || before == null || trigger == null || before < trigger)) {
            try {
                publishSnapshot()
            } catch (failure: Exception) {
                runController.throwIfCancelled()
                if (!final) throw failure
                committedSnapshot = createSnapshot(messages)
                onEvent(AgentEvent.ContextCompaction(operationId, "failed", before,
                    reasonCode = "CONTEXT_CHECKPOINT_FAILED"))
            }
            return
        }
        val operation = java.util.UUID.randomUUID().toString()
        onEvent(AgentEvent.ContextCompaction(operation, AgentEvent.ContextCompaction.PHASE_STARTED, before))
        try {
            val candidate = AgentContextCompactor(
                config, provider, runController, roleplay = roleplay,
                calibration = budget.calibrationValue(),
            ).compact(
                messages, systemCount, sensitiveIds(),
                // P1-2（2026-10-16）：传入本轮实测 input tokens 作为「压后超线判定」的基准，
                // 与真实请求同口径（同时消除「估算分母含/不含 tools」的口径不一致）。
                observedInputTokens = before,
                // 把清单概览写进摘要，避免压缩后模型忘记「我在第几步」。
                planOverview = planOverview(),
            )
            runController.throwIfCancelled()
            val wasCompacted = compacted
            compacted = true
            try {
                publishSnapshot(candidate)
            } catch (failure: Exception) {
                compacted = wasCompacted
                throw failure
            }
            while (messages.length() > 0) messages.remove(messages.length() - 1)
            for (index in 0 until candidate.length()) messages.put(candidate.getJSONObject(index))
            inputTokens = null
            onEvent(AgentEvent.ContextCompaction(operation, AgentEvent.ContextCompaction.PHASE_COMPLETED, before))
        } catch (failure: Exception) {
            runController.throwIfCancelled()
            compactionBlocked = true
            onEvent(AgentEvent.ContextCompaction(operation, "failed", before,
                reasonCode = (failure as? AgentModelFailure)?.code ?: "CONTEXT_SUMMARY_FAILED"))
            if (!final) throw failure
            // 已完成的回答仍成功交付；完整快照随终态 outbox 保存，不依赖先前检查点写入成功。
            committedSnapshot = createSnapshot(messages)
        }
    }

    private fun durableHistory(source: JSONArray): List<AgentModelClient.ConversationMessage> {
        val durable = JSONArray()
        for (index in systemCount until source.length()) {
            val message = source.getJSONObject(index)
            if (!message.optBoolean("_eta_observation")) durable.put(message)
        }
        return AgentConversationCodec.transcript(durable, 0, sensitiveIds())
    }

    companion object {
        /**
         * 触发压缩的窗口占用比例（0.8，与 deepseek-harness 的 thresholdRatio 默认值一致；
         * fork 早期曾用 0.85 → 0.75，v3.9.0 起定版 0.8）。
         * 真源在 [AgentContextBudget]，实际阈值取窗口比例与「窗口 − 输出预留 − 余量」的较小者，见 `triggerTokens`。
         */
        const val TRIGGER_RATIO = AgentContextBudget.TRIGGER_RATIO

        /** C3b：连续未收到 usage 达到此轮数才发 CompactionUnavailable 诊断事件。 */
        private const val MISSING_USAGE_EVENT_THRESHOLD = 3
    }
}
