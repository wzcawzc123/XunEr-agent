package io.github.mangi.eta.ui.model

import androidx.compose.runtime.Immutable
import io.github.mangi.eta.data.repository.AgentMemoryHistoryEntry
import io.github.mangi.eta.data.repository.AgentMemoryStore

/** 记忆页当前编辑的目标仓库：long=长期（默认）/ short=短期。 */
enum class MemoryTargetUi { Long, Short }
@Immutable
internal data class AgentMemoryUiState(
    val enabled: Boolean = true,
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    /** 整理（rollup）进行中；与 [isSaving] 分离，保存栏不再误显「保存中」。 */
    val isRollingUp: Boolean = false,
    val draft: String = "",
    val savedContent: String = "",
    /** A2（v3.13.3）：显式脏标记，替代每次重组做 draft != savedContent 全量比较。 */
    val dirty: Boolean = false,
    val draftBytes: Int = 0,
    val maxBytes: Int = AgentMemoryStore.MAX_FILE_BYTES,
    val coreBudgetChars: Int = 8_000,
    /** 当前内容实际会注入的核心区字符数（与注入链路同一算法），水位条用。 */
    val coreUsedChars: Int = 0,
    /** 核心区是否超出注入预算（超出部分模型看不到）。 */
    val coreTruncated: Boolean = false,
    /** 当前编辑目标（long/short）；切换时重新加载对应仓库。 */
    val target: MemoryTargetUi = MemoryTargetUi.Long,
    /** 搜索关键词（仅过滤当前 draft 的展示，不落盘）。 */
    val searchQuery: String = "",
    /** UI 保存的乐观锁基准（进入页面/上次成功保存/刷新时的 revision）。 */
    val baseRevision: String = "",
    /** 写前历史快照列表（最新在前）。 */
    val history: List<AgentMemoryHistoryEntry> = emptyList(),
    /** 整理产出的候选文本：非空时弹预览确认，用户确认后才追加进长期。 */
    val pendingRollupResult: String? = null,
    val notice: String? = null,
) {
    val hasUnsavedChanges: Boolean get() = dirty
    val canSave: Boolean
        get() = !isLoading && !isSaving && dirty &&
            draftBytes <= maxBytes
    val coreUsagePercent: Int
        get() = if (coreBudgetChars <= 0) 0 else (coreUsedChars * 100 / coreBudgetChars).coerceIn(0, 999)
}
