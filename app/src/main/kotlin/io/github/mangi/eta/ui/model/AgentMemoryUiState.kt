package io.github.mangi.eta.ui.model

import androidx.compose.runtime.Immutable
import io.github.mangi.eta.data.repository.AgentMemoryStore

/** 记忆页当前编辑的目标仓库：long=长期（默认）/ short=短期。 */
enum class MemoryTargetUi { Long, Short }
@Immutable
internal data class AgentMemoryUiState(
    val enabled: Boolean = true,
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val draft: String = "",
    val savedContent: String = "",
    /** A2（v3.13.3）：显式脏标记，替代每次重组做 draft != savedContent 全量比较。 */
    val dirty: Boolean = false,
    val draftBytes: Int = 0,
    val maxBytes: Int = AgentMemoryStore.MAX_FILE_BYTES,
    val coreBudgetChars: Int = 8_000,
    /** 当前编辑目标（long/short）；切换时重新加载对应仓库。 */
    val target: MemoryTargetUi = MemoryTargetUi.Long,
    /** 搜索关键词（仅过滤当前 draft 的展示，不落盘）。 */
    val searchQuery: String = "",
    val notice: String? = null,
) {
    val hasUnsavedChanges: Boolean get() = dirty
    val canSave: Boolean
        get() = !isLoading && !isSaving && dirty &&
            draftBytes <= maxBytes
}
