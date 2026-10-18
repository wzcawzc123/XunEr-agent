package io.github.mangi.eta.ui.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.memory.AgentMemoryContextBuilder
import io.github.mangi.eta.agent.model.MemoryRollup
import io.github.mangi.eta.agent.model.ProviderClientFactory
import io.github.mangi.eta.agent.model.RollupOutcome
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.data.repository.AgentMemoryRepository
import io.github.mangi.eta.data.repository.MemoryRepository
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.data.repository.ShortMemoryRepository
import io.github.mangi.eta.ui.model.AgentMemoryUiState
import io.github.mangi.eta.ui.model.MemoryTargetUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 记忆页的读取、编辑与保存状态；与会话和 Agent run 无共享状态。 */
internal class AgentMemoryStore(
    context: Context,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext

    var memoryState by mutableStateOf(AgentMemoryUiState())
        private set

    /** target → 对应仓库（long 默认，short 独立文件）；两仓库接口一致。 */
    private fun repositoryFor(target: MemoryTargetUi): MemoryRepository =
        if (target == MemoryTargetUi.Short) ShortMemoryRepository else AgentMemoryRepository

    fun refreshMemory() {
        memoryState = memoryState.copy(isLoading = true)
        val target = memoryState.target
        val repo = repositoryFor(target)
        scope.launch(Dispatchers.IO) {
            runCatching {
                val snapshot = repo.snapshot()
                val enabled = AgentMemoryRepository.isEnabled()
                val contextWindow = RuntimeConfigRepository.currentRuntimeConfig()?.contextWindow
                Triple(snapshot, enabled, AgentMemoryContextBuilder.coreBudgetChars(contextWindow))
            }.fold(
                onSuccess = { (snapshot, enabled, coreBudget) ->
                    withContext(Dispatchers.Main) {
                        memoryState = memoryState.copy(
                            enabled = enabled,
                            isLoading = false,
                            draft = snapshot.content,
                            savedContent = snapshot.content,
                            dirty = false,
                            draftBytes = snapshot.byteSize,
                            coreBudgetChars = coreBudget,
                        )
                    }
                },
                onFailure = { throwable ->
                    AndroidAgentLogger.warnThrottled("agent_memory_ui_load_failed") {
                        "Agent memory UI load failed: type=${throwable.safeLogType()}"
                    }
                    withContext(Dispatchers.Main) {
                        memoryState = memoryState.copy(
                            isLoading = false,
                            notice = appContext.getString(R.string.state_ui_failed_to_read_memory_please_try_again_later_caeaa6),
                        )
                    }
                },
            )
        }
    }

    /** 切换 long/short 目标：丢弃未保存草稿并重新加载对应仓库（草稿不跨仓库保留，防误存）。 */
    fun switchTarget(target: MemoryTargetUi) {
        if (target == memoryState.target || memoryState.isLoading) return
        memoryState = memoryState.copy(target = target, searchQuery = "", notice = null)
        refreshMemory()
    }

    fun updateMemoryDraft(content: String) {
        // A1（v3.13.3）：击键路径不再全量 toByteArray（242KB 每键编码是卡顿根源）。
        // draftBytes 保持上次精确值，只在保存/丢弃时按需重算（见 saveMemory / resetDraft）。
        memoryState = memoryState.copy(
            draft = content,
            dirty = true,
            notice = null,
        )
    }

    /** 更新搜索关键词（仅过滤 draft 展示，不落盘）。 */
    fun updateSearchQuery(query: String) {
        memoryState = memoryState.copy(searchQuery = query.trim())
    }

    /**
     * 手动触发短期→长期 rollup（spec memory-upgrade §3.2）。
     * 用默认模型的运行时配置构建 provider；LLM 失败/追加冲突都不改短期记忆，
     * 结果以 notice 呈现，完成后刷新两个仓库。
     */
    fun rollupMemory() {
        if (memoryState.isSaving) return
        memoryState = memoryState.copy(isSaving = true, notice = null)
        scope.launch(Dispatchers.IO) {
            val outcome = runCatching {
                val config = RuntimeConfigRepository.runtimeConfigFor(null)
                if (config == null) {
                    RollupOutcome(false, "未配置可用的模型，无法整理记忆。")
                } else {
                    val provider = io.github.mangi.eta.agent.model.ProviderClientFactory.getClient(config)
                    MemoryRollup(
                        config = config,
                        provider = provider,
                        controller = AgentRunController(),
                    ).run()
                }
            }.getOrElse { throwable ->
                io.github.mangi.eta.core.AndroidAgentLogger.warnThrottled("agent_memory_rollup_failed") {
                    "Agent memory rollup failed: type=${throwable.safeLogType()}"
                }
                RollupOutcome(false, "记忆整理失败：${throwable.message ?: throwable.javaClass.simpleName}")
            }
            withContext(Dispatchers.Main) {
                memoryState = memoryState.copy(isSaving = false, notice = outcome.message)
            }
            refreshMemory()
        }
    }

    fun setMemoryEnabled(enabled: Boolean) {
        scope.launch(Dispatchers.IO) {
            runCatching { AgentMemoryRepository.setEnabled(enabled) }
                .fold(
                    onSuccess = {
                        withContext(Dispatchers.Main) {
                            memoryState = memoryState.copy(enabled = enabled, notice = null)
                        }
                    },
                    onFailure = { throwable ->
                        AndroidAgentLogger.warnThrottled("agent_memory_toggle_failed") {
                            "Agent memory setting update failed: type=${throwable.safeLogType()}"
                        }
                        withContext(Dispatchers.Main) {
                            memoryState = memoryState.copy(notice = appContext.getString(R.string.state_ui_memory_switch_failed_to_save_83b5d6))
                        }
                    },
                )
        }
    }

    fun saveMemory() {
        if (!memoryState.canSave) return
        val target = memoryState.draft
        val repo = repositoryFor(memoryState.target)
        memoryState = memoryState.copy(isSaving = true, notice = null)
        scope.launch(Dispatchers.IO) {
            runCatching { repo.replaceAll(target) }
                .fold(
                    onSuccess = { snapshot ->
                        withContext(Dispatchers.Main) {
                            memoryState = memoryState.copy(
                                isSaving = false,
                                savedContent = snapshot.content,
                                draft = if (memoryState.draft == target) {
                                    snapshot.content
                                } else {
                                    memoryState.draft
                                },
                                // A1：保存是低频操作，这里算一次精确字节数（编辑击键不再算）。
                                dirty = false,
                                draftBytes = snapshot.byteSize,
                                notice = appContext.getString(R.string.state_ui_memory_saved_a2c61c),
                            )
                        }
                    },
                    onFailure = { throwable ->
                        AndroidAgentLogger.warnThrottled("agent_memory_ui_save_failed") {
                            "Agent memory UI save failed: type=${throwable.safeLogType()}"
                        }
                        withContext(Dispatchers.Main) {
                            memoryState = memoryState.copy(
                                isSaving = false,
                                notice = throwable.message ?: appContext.getString(R.string.state_ui_memory_save_failed_1f501e),
                            )
                        }
                    },
                )
        }
    }

    fun clearMemory() {
        if (memoryState.isSaving) return
        val repo = repositoryFor(memoryState.target)
        memoryState = memoryState.copy(isSaving = true, notice = null)
        scope.launch(Dispatchers.IO) {
            runCatching { repo.replaceAll("") }
                .fold(
                    onSuccess = {
                        withContext(Dispatchers.Main) {
                            memoryState = memoryState.copy(
                                isSaving = false,
                                draft = "",
                                savedContent = "",
                                dirty = false,
                                draftBytes = 0,
                                notice = appContext.getString(R.string.state_ui_memory_cleared_b415bb),
                            )
                        }
                    },
                    onFailure = { throwable ->
                        AndroidAgentLogger.warnThrottled("agent_memory_ui_clear_failed") {
                            "Agent memory UI clear failed: type=${throwable.safeLogType()}"
                        }
                        withContext(Dispatchers.Main) {
                            memoryState = memoryState.copy(
                                isSaving = false,
                                notice = throwable.message ?: appContext.getString(R.string.state_ui_memory_clearing_failed_7f0aba),
                            )
                        }
                    },
                )
        }
    }

    fun dismissMemoryNotice() {
        memoryState = memoryState.copy(notice = null)
    }
}
