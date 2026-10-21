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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 记忆页的读取、编辑与保存状态；与会话和 Agent run 无共享状态。
 *
 * 4.0 升级（docs/MEMORY_UPGRADE_PLAN.md）：
 * - 保存走 revision 乐观锁，UI 编辑不再吞掉 Agent 的并发写入；
 * - 击键后防抖精确重算字节与脏标记（改回原样保存栏自动消失），不再有超限提示失效窗口；
 * - 整理（rollup）拆成「提炼 → 预览确认 → 落盘」，且忙碌状态与保存分离；
 * - 历史快照列表/恢复入口。
 */
internal class AgentMemoryStore(
    context: Context,
    private val scope: CoroutineScope,
    /** IO 调度器；测试注入可控调度器做确定性断言。 */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** 主线程调度器（状态更新回切用）；测试注入可控调度器。 */
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
    /** 击键后防抖重算间隔；测试注入 0 免等待。 */
    private val draftRecountDebounceMs: Long = DRAFT_RECOUNT_DEBOUNCE_MS,
) {
    private val appContext = context.applicationContext

    var memoryState by mutableStateOf(AgentMemoryUiState())
        private set

    /** 字节/脏标记防抖重算的待执行任务（每次击键取消重排）。 */
    private var draftRecountJob: Job? = null

    /** target → 对应仓库（long 默认，short 独立文件）；两仓库接口一致。 */
    private fun repositoryFor(target: MemoryTargetUi): MemoryRepository =
        if (target == MemoryTargetUi.Short) ShortMemoryRepository else AgentMemoryRepository

    fun refreshMemory() {
        memoryState = memoryState.copy(isLoading = true)
        val target = memoryState.target
        val repo = repositoryFor(target)
        scope.launch(ioDispatcher) {
            runCatching {
                val snapshot = repo.snapshot()
                val enabled = AgentMemoryRepository.isEnabled()
                val contextWindow = RuntimeConfigRepository.currentRuntimeConfig()?.contextWindow
                val budget = AgentMemoryContextBuilder.coreBudgetChars(contextWindow)
                val gauge = AgentMemoryContextBuilder.build(snapshot, contextWindow)
                val history = repo.listHistory()
                MemoryLoad(snapshot, enabled, budget, gauge, history)
            }.fold(
                onSuccess = { load ->
                    withContext(mainDispatcher) {
                        memoryState = memoryState.copy(
                            enabled = load.enabled,
                            isLoading = false,
                            draft = load.snapshot.content,
                            savedContent = load.snapshot.content,
                            dirty = false,
                            draftBytes = load.snapshot.byteSize,
                            coreBudgetChars = load.coreBudget,
                            coreUsedChars = load.gauge.coreContent.length,
                            coreTruncated = load.gauge.coreTruncated,
                            baseRevision = load.snapshot.revision,
                            history = load.history,
                        )
                    }
                },
                onFailure = { throwable ->
                    AndroidAgentLogger.warnThrottled("agent_memory_ui_load_failed") {
                        "Agent memory UI load failed: type=${throwable.safeLogType()}"
                    }
                    withContext(mainDispatcher) {
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
        draftRecountJob?.cancel()
        memoryState = memoryState.copy(target = target, searchQuery = "", notice = null, pendingRollupResult = null)
        refreshMemory()
    }

    fun updateMemoryDraft(content: String) {
        // 击键路径不做全量 toByteArray（242KB 每键编码是卡顿根源）：
        // 先乐观置脏保住手感，再防抖 [DRAFT_RECOUNT_DEBOUNCE_MS] 在 IO 线程精确重算
        // 字节数与「是否真的改了」（改回原样 → 保存栏自动消失）。
        memoryState = memoryState.copy(
            draft = content,
            dirty = true,
            notice = null,
        )
        draftRecountJob?.cancel()
        draftRecountJob = scope.launch(ioDispatcher) {
            delay(draftRecountDebounceMs)
            val bytes = content.toByteArray(Charsets.UTF_8).size
            val reallyDirty = content != memoryState.savedContent
            withContext(mainDispatcher) {
                memoryState = memoryState.copy(
                    draftBytes = bytes,
                    dirty = reallyDirty,
                )
            }
        }
    }

    /**
     * 丢弃未保存草稿：恢复为已保存内容并清脏。
     *
     * 不能复用 [updateMemoryDraft]（传 savedContent）—— 那条路径永远置 dirty=true，
     * 保存栏的可见性靠 dirty 驱动，会导致「点放弃后保存栏还在」（真机反馈）。
     * 与 saveMemory 同构：低频操作，顺带重算一次精确 draftBytes。
     */
    fun resetDraft() {
        draftRecountJob?.cancel()
        val saved = memoryState.savedContent
        memoryState = memoryState.copy(
            draft = saved,
            dirty = false,
            draftBytes = saved.toByteArray().size,
            notice = null,
        )
    }

    /** 更新搜索关键词（仅过滤 draft 展示，不落盘）。 */
    fun updateSearchQuery(query: String) {
        // 不 trim：输入框是受控的，这里 trim 会把刚敲的空格吃掉，
        // 导致「long term」无法输入（真机反馈）。空白只在过滤时忽略。
        memoryState = memoryState.copy(searchQuery = query)
    }

    /**
     * 整理第一步：短期→长期提炼（spec memory-upgrade §3.2），只产出候选文本。
     * 用默认模型的运行时配置构建 provider；LLM 失败/无需整理以 notice 呈现；
     * 成功则把候选放进 [AgentMemoryUiState.pendingRollupResult]，等用户预览确认。
     */
    fun proposeRollup() {
        if (memoryState.isRollingUp || memoryState.isSaving) return
        memoryState = memoryState.copy(isRollingUp = true, notice = null)
        scope.launch(ioDispatcher) {
            val draft = runCatching {
                val config = RuntimeConfigRepository.runtimeConfigFor(null)
                if (config == null) {
                    io.github.mangi.eta.agent.model.RollupDraft(
                        null,
                        "未配置可用的模型，无法整理记忆。",
                    )
                } else {
                    val provider = ProviderClientFactory.getClient(config)
                    MemoryRollup(
                        config = config,
                        provider = provider,
                        controller = AgentRunController(),
                    ).propose()
                }
            }.getOrElse { throwable ->
                AndroidAgentLogger.warnThrottled("agent_memory_rollup_failed") {
                    "Agent memory rollup failed: type=${throwable.safeLogType()}"
                }
                io.github.mangi.eta.agent.model.RollupDraft(
                    null,
                    "记忆整理失败：${throwable.message ?: throwable.javaClass.simpleName}",
                )
            }
            withContext(mainDispatcher) {
                memoryState = memoryState.copy(
                    isRollingUp = false,
                    pendingRollupResult = draft.candidates,
                    notice = draft.message.takeIf { it.isNotEmpty() },
                )
            }
        }
    }

    /** 整理第二步：用户在预览里点「追加到长期」后落盘（revision 乐观锁追加）。 */
    fun commitRollup() {
        val candidates = memoryState.pendingRollupResult ?: return
        if (memoryState.isRollingUp) return
        memoryState = memoryState.copy(isRollingUp = true, pendingRollupResult = null, notice = null)
        scope.launch(ioDispatcher) {
            val outcome = runCatching {
                val config = RuntimeConfigRepository.runtimeConfigFor(null)
                if (config == null) {
                    RollupOutcome(false, "未配置可用的模型，无法整理记忆。")
                } else {
                    val provider = ProviderClientFactory.getClient(config)
                    MemoryRollup(
                        config = config,
                        provider = provider,
                        controller = AgentRunController(),
                    ).commit(candidates)
                }
            }.getOrElse { throwable ->
                AndroidAgentLogger.warnThrottled("agent_memory_rollup_commit_failed") {
                    "Agent memory rollup commit failed: type=${throwable.safeLogType()}"
                }
                RollupOutcome(false, "记忆整理失败：${throwable.message ?: throwable.javaClass.simpleName}")
            }
            withContext(mainDispatcher) {
                memoryState = memoryState.copy(isRollingUp = false, notice = outcome.message)
            }
            refreshMemory()
        }
    }

    /** 放弃整理结果：关闭预览，不写入长期。 */
    fun dismissRollupResult() {
        memoryState = memoryState.copy(pendingRollupResult = null)
    }

    fun setMemoryEnabled(enabled: Boolean) {
        scope.launch(ioDispatcher) {
            runCatching { AgentMemoryRepository.setEnabled(enabled) }
                .fold(
                    onSuccess = {
                        withContext(mainDispatcher) {
                            memoryState = memoryState.copy(enabled = enabled, notice = null)
                        }
                    },
                    onFailure = { throwable ->
                        AndroidAgentLogger.warnThrottled("agent_memory_toggle_failed") {
                            "Agent memory setting update failed: type=${throwable.safeLogType()}"
                        }
                        withContext(mainDispatcher) {
                            memoryState = memoryState.copy(notice = appContext.getString(R.string.state_ui_memory_switch_failed_to_save_83b5d6))
                        }
                    },
                )
        }
    }

    fun saveMemory() {
        if (!memoryState.canSave) return
        val target = memoryState.draft
        val baseRevision = memoryState.baseRevision
        val repo = repositoryFor(memoryState.target)
        memoryState = memoryState.copy(isSaving = true, notice = null)
        scope.launch(ioDispatcher) {
            // revision 乐观锁：UI 编辑期间 Agent 的 memory_write 不再被静默覆盖。
            runCatching { repo.replaceAllIfRevision(target, baseRevision) }
                .fold(
                    onSuccess = { result ->
                        when (result) {
                            is io.github.mangi.eta.data.repository.AgentMemoryWriteResult.Conflict -> {
                                // 整理期间被 Agent 改过：不覆盖，载入最新内容让用户决定。
                                AndroidAgentLogger.warnThrottled("agent_memory_ui_save_conflict") {
                                    "Agent memory UI save hit revision conflict"
                                }
                                withContext(mainDispatcher) {
                                    memoryState = memoryState.copy(
                                        isSaving = false,
                                        notice = appContext.getString(R.string.memory_save_conflict),
                                    )
                                }
                                refreshMemory()
                            }
                            is io.github.mangi.eta.data.repository.AgentMemoryWriteResult.Success -> {
                                val snapshot = result.snapshot
                                // 真机实测（2026-10-22）：保存会留下历史快照，但状态里的历史列表
                                // 只在 refreshMemory 时加载——不同步更新的话，保存后打开历史对话框
                                // 是空的（磁盘上快照明明在），必须重进页面才能看到。
                                val history = runCatching { repo.listHistory() }.getOrDefault(emptyList())
                                withContext(mainDispatcher) {
                                    memoryState = memoryState.copy(
                                        isSaving = false,
                                        savedContent = snapshot.content,
                                        draft = if (memoryState.draft == target) {
                                            snapshot.content
                                        } else {
                                            memoryState.draft
                                        },
                                        // A1：保存是低频操作，这里算一次精确字节数（编辑击键走防抖）。
                                        dirty = false,
                                        draftBytes = snapshot.byteSize,
                                        baseRevision = snapshot.revision,
                                        history = history,
                                        notice = appContext.getString(R.string.state_ui_memory_saved_a2c61c),
                                    )
                                }
                            }
                        }
                    },
                    onFailure = { throwable ->
                        AndroidAgentLogger.warnThrottled("agent_memory_ui_save_failed") {
                            "Agent memory UI save failed: type=${throwable.safeLogType()}"
                        }
                        withContext(mainDispatcher) {
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
        scope.launch(ioDispatcher) {
            // replaceAll 内部会先把当前内容记入历史快照，清空可回滚。
            runCatching { repo.replaceAll("") }
                .fold(
                onSuccess = { snapshot ->
                    // 清空也留历史快照，同步列表（见 saveMemory 同名注释）。
                    val history = runCatching { repo.listHistory() }.getOrDefault(emptyList())
                    withContext(mainDispatcher) {
                        memoryState = memoryState.copy(
                            isSaving = false,
                            draft = "",
                            savedContent = "",
                            dirty = false,
                            draftBytes = 0,
                            baseRevision = snapshot.revision,
                            history = history,
                            notice = appContext.getString(R.string.state_ui_memory_cleared_b415bb),
                        )
                    }
                },
                    onFailure = { throwable ->
                        AndroidAgentLogger.warnThrottled("agent_memory_ui_clear_failed") {
                            "Agent memory UI clear failed: type=${throwable.safeLogType()}"
                        }
                        withContext(mainDispatcher) {
                            memoryState = memoryState.copy(
                                isSaving = false,
                                notice = throwable.message ?: appContext.getString(R.string.state_ui_memory_clearing_failed_7f0aba),
                            )
                        }
                    },
                )
        }
    }

    /** 从历史快照恢复（恢复前当前内容会自动记入历史，回滚也可回滚）。 */
    fun restoreHistory(id: String) {
        if (memoryState.isSaving) return
        val repo = repositoryFor(memoryState.target)
        memoryState = memoryState.copy(isSaving = true, notice = null)
        scope.launch(ioDispatcher) {
            runCatching { repo.restoreHistory(id) }
                .fold(
                    onSuccess = { snapshot ->
                        withContext(mainDispatcher) {
                            memoryState = memoryState.copy(
                                isSaving = false,
                                draft = snapshot.content,
                                savedContent = snapshot.content,
                                dirty = false,
                                draftBytes = snapshot.byteSize,
                                baseRevision = snapshot.revision,
                                notice = appContext.getString(R.string.memory_restored),
                            )
                        }
                    },
                    onFailure = { throwable ->
                        AndroidAgentLogger.warnThrottled("agent_memory_ui_restore_failed") {
                            "Agent memory UI restore failed: type=${throwable.safeLogType()}"
                        }
                        withContext(mainDispatcher) {
                            memoryState = memoryState.copy(
                                isSaving = false,
                                notice = throwable.message ?: appContext.getString(R.string.state_ui_memory_save_failed_1f501e),
                            )
                        }
                    },
                )
            // 恢复改变了历史列表（多一条当前内容快照），恢复完成后再刷新。
            refreshMemory()
        }
    }

    fun dismissMemoryNotice() {
        memoryState = memoryState.copy(notice = null)
    }

    /** [refreshMemory] 的一次完整装载结果（避开 Kotlin 没有四元组的问题）。 */
    private data class MemoryLoad(
        val snapshot: io.github.mangi.eta.data.repository.AgentMemorySnapshot,
        val enabled: Boolean,
        val coreBudget: Int,
        val gauge: io.github.mangi.eta.agent.memory.AgentMemoryContext,
        val history: List<io.github.mangi.eta.data.repository.AgentMemoryHistoryEntry>,
    )

    private companion object {
        /** 击键后防抖重算的间隔：兼顾手感与字节/脏标记的精确性。 */
        const val DRAFT_RECOUNT_DEBOUNCE_MS = 300L
    }
}
