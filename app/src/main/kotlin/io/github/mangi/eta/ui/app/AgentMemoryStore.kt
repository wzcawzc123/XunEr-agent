package io.github.mangi.eta.ui.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.memory.AgentMemoryContextBuilder
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.data.repository.AgentMemoryHistoryEntry
import io.github.mangi.eta.data.repository.AgentMemoryRepository
import io.github.mangi.eta.data.repository.AgentMemorySnapshot
import io.github.mangi.eta.data.repository.AgentMemoryWriteResult
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.ui.model.AgentMemoryUiState
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

    fun refreshMemory() {
        memoryState = memoryState.copy(isLoading = true, notice = null)
        scope.launch(Dispatchers.IO) {
            runCatching {
                val snapshot = AgentMemoryRepository.snapshot()
                val enabled = AgentMemoryRepository.isEnabled()
                val contextWindow = RuntimeConfigRepository.currentRuntimeConfig()?.contextWindow
                val history = runCatching { AgentMemoryRepository.listHistory() }.getOrDefault(emptyList())
                MemoryLoad(snapshot, enabled, AgentMemoryContextBuilder.coreBudgetChars(contextWindow), history)
            }.fold(
                onSuccess = { loaded ->
                    withContext(Dispatchers.Main) {
                        memoryState = AgentMemoryUiState(
                            enabled = loaded.enabled,
                            isLoading = false,
                            draft = loaded.snapshot.content,
                            savedContent = loaded.snapshot.content,
                            savedRevision = loaded.snapshot.revision,
                            draftBytes = loaded.snapshot.byteSize,
                            coreBudgetChars = loaded.coreBudget,
                            history = loaded.history,
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

    private data class MemoryLoad(
        val snapshot: AgentMemorySnapshot,
        val enabled: Boolean,
        val coreBudget: Int,
        val history: List<AgentMemoryHistoryEntry>,
    )

    fun updateMemoryDraft(content: String) {
        memoryState = memoryState.copy(
            draft = content,
            draftBytes = content.toByteArray(Charsets.UTF_8).size,
            notice = null,
        )
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
        // 带上"打开编辑时的 revision"：编辑期间模型可能也写了记忆，无条件覆盖会把它吞掉，
        // 而用户会以为保存的是最新版。冲突时宁可让用户重看一遍。
        val baseRevision = memoryState.savedRevision
        memoryState = memoryState.copy(isSaving = true, notice = null)
        scope.launch(Dispatchers.IO) {
            runCatching { AgentMemoryRepository.replaceAllIfRevision(target, baseRevision) }
                .fold(
                    onSuccess = { result ->
                        val history = runCatching { AgentMemoryRepository.listHistory() }.getOrDefault(emptyList())
                        withContext(Dispatchers.Main) {
                            when (result) {
                                is AgentMemoryWriteResult.Success -> memoryState = memoryState.copy(
                                    isSaving = false,
                                    savedContent = result.snapshot.content,
                                    savedRevision = result.snapshot.revision,
                                    draft = if (memoryState.draft == target) {
                                        result.snapshot.content
                                    } else {
                                        memoryState.draft
                                    },
                                    draftBytes = memoryState.draft.toByteArray(Charsets.UTF_8).size,
                                    history = history,
                                    notice = appContext.getString(R.string.state_ui_memory_saved_a2c61c),
                                )
                                is AgentMemoryWriteResult.Conflict -> memoryState = memoryState.copy(
                                    isSaving = false,
                                    // 冲突时把磁盘上的最新内容拉回来，用户的草稿留在 draft 里由他决定取舍。
                                    savedContent = result.snapshot.content,
                                    savedRevision = result.snapshot.revision,
                                    history = history,
                                    notice = appContext.getString(R.string.memory_conflict_reload),
                                )
                            }
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

    /**
     * 回滚到某份写前快照。
     *
     * 恢复本身也会先记录当前内容，所以「回滚」是可以再回滚的 —— 不会因为一次误点把现状也弄丢。
     */
    fun restoreHistory(id: String) {
        if (memoryState.isSaving) return
        memoryState = memoryState.copy(isSaving = true, notice = null)
        scope.launch(Dispatchers.IO) {
            runCatching { AgentMemoryRepository.restoreHistory(id) }
                .fold(
                    onSuccess = { snapshot ->
                        val history = runCatching { AgentMemoryRepository.listHistory() }.getOrDefault(emptyList())
                        withContext(Dispatchers.Main) {
                            memoryState = memoryState.copy(
                                isSaving = false,
                                draft = snapshot.content,
                                savedContent = snapshot.content,
                                savedRevision = snapshot.revision,
                                draftBytes = snapshot.byteSize,
                                history = history,
                                notice = appContext.getString(R.string.memory_history_restored),
                            )
                        }
                    },
                    onFailure = { throwable ->
                        AndroidAgentLogger.warnThrottled("agent_memory_ui_restore_failed") {
                            "Agent memory restore failed: type=${throwable.safeLogType()}"
                        }
                        withContext(Dispatchers.Main) {
                            memoryState = memoryState.copy(
                                isSaving = false,
                                notice = throwable.message ?: appContext.getString(R.string.memory_history_restore_failed),
                            )
                        }
                    },
                )
        }
    }

    fun clearMemory() {
        if (memoryState.isSaving) return
        memoryState = memoryState.copy(isSaving = true, notice = null)
        scope.launch(Dispatchers.IO) {
            runCatching { AgentMemoryRepository.replaceAll("") }
                .fold(
                    onSuccess = {
                        withContext(Dispatchers.Main) {
                            memoryState = memoryState.copy(
                                isSaving = false,
                                draft = "",
                                savedContent = "",
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
