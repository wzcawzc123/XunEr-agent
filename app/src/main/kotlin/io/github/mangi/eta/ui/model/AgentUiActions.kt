package io.github.mangi.eta.ui.model

import io.github.mangi.eta.data.model.ReasoningEffort

sealed interface AgentHomeAction {
    data object CompactContext : AgentHomeAction
    /** 点击输入栏的「计划」按钮：NORMAL→进入规划，规划/执行中→退出。 */
    data object TogglePlanMode : AgentHomeAction
    /** 审批条上的「批准」：等价于用户发出批准指令，进入执行。 */
    data object ApprovePlan : AgentHomeAction
    /** 审批条上的「取消」：放弃计划并回到普通对话。 */
    data object CancelPlan : AgentHomeAction
    data class ReasoningEffortChanged(val effort: ReasoningEffort) : AgentHomeAction
    data class ModelSelected(val modelId: String) : AgentHomeAction
    data class VisionToggled(val providerId: String, val modelId: String, val vision: Boolean) :
        AgentHomeAction
    data class SubmitMessage(val text: String) : AgentHomeAction
    data object StopRun : AgentHomeAction
    data class ImageAttached(val uri: String) : AgentHomeAction
    data class RemoveImage(val id: String) : AgentHomeAction
    data class FilesAttached(val uris: List<String>) : AgentHomeAction
    data class FolderAttached(val uri: String) : AgentHomeAction
    data class FilePathAttached(val path: String) : AgentHomeAction
    data class RemoveFileReference(val id: String) : AgentHomeAction
    data class EditMessage(val id: String) : AgentHomeAction
    data object CancelMessageEdit : AgentHomeAction
    data class DeleteMessage(val id: String) : AgentHomeAction
    data class RegenerateMessage(val id: String) : AgentHomeAction
    data class SelectReplyCandidate(val id: String, val index: Int) : AgentHomeAction
    data object OpenTools : AgentHomeAction
    data object OpenSkills : AgentHomeAction
    data object OpenPermissions : AgentHomeAction
    data object OpenSystemEnhance : AgentHomeAction
    data object OpenSettings : AgentHomeAction
    data object OpenBrowser : AgentHomeAction
    data object ExpandRunTrace : AgentHomeAction
}

sealed interface PermissionHealthAction {
    data class OpenItemAction(val itemId: String) : PermissionHealthAction
    data object NavigateBack : PermissionHealthAction
}

sealed interface AgentChatAction {
    data object CompactContext : AgentChatAction
    /** 点击输入栏的「计划」按钮：NORMAL→进入规划，规划/执行中→退出。 */
    data object TogglePlanMode : AgentChatAction
    /** 审批条上的「批准」：等价于用户发出批准指令，进入执行。 */
    data object ApprovePlan : AgentChatAction
    /** 审批条上的「取消」：放弃计划并回到普通对话。 */
    data object CancelPlan : AgentChatAction
    data object ResumeInterruptedRun : AgentChatAction
    data object NavigateBack : AgentChatAction
    data class ReasoningEffortChanged(val effort: ReasoningEffort) : AgentChatAction
    data class ModelSelected(val modelId: String) : AgentChatAction
    data class VisionToggled(val providerId: String, val modelId: String, val vision: Boolean) :
        AgentChatAction
    data class SubmitMessage(val text: String) : AgentChatAction
    data object StopRun : AgentChatAction
    data object OpenBrowser : AgentChatAction
    data class ImageAttached(val uri: String) : AgentChatAction
    data class RemoveImage(val id: String) : AgentChatAction
    data class FilesAttached(val uris: List<String>) : AgentChatAction
    data class FolderAttached(val uri: String) : AgentChatAction
    data class FilePathAttached(val path: String) : AgentChatAction
    data class RemoveFileReference(val id: String) : AgentChatAction
    data class EditMessage(val id: String) : AgentChatAction
    data object CancelMessageEdit : AgentChatAction
    data class DeleteMessage(val id: String) : AgentChatAction
    data class RegenerateMessage(val id: String) : AgentChatAction
    data class SelectReplyCandidate(val id: String, val index: Int) : AgentChatAction
}

sealed interface AgentToolsAction {
    data object OpenEnhancements : AgentToolsAction
    data object OpenPermissions : AgentToolsAction
    data object NavigateBack : AgentToolsAction
    data object OpenBrowser : AgentToolsAction
}

sealed interface AgentSkillsAction {
    data object NavigateBack : AgentSkillsAction
    data class ImportZip(val uri: String) : AgentSkillsAction
    data object ConfirmZipReplacement : AgentSkillsAction
    data object CancelZipReplacement : AgentSkillsAction
    data object DismissNotice : AgentSkillsAction
    data class ToggleSkill(val skillId: String, val enabled: Boolean) : AgentSkillsAction
    data class DeleteSkill(val skillId: String) : AgentSkillsAction
    data class ReinstallBuiltin(val skillId: String) : AgentSkillsAction
}

sealed interface AgentSystemEnhanceAction {
    data object NavigateBack : AgentSystemEnhanceAction
    data object RequestRoot : AgentSystemEnhanceAction
    data object RefreshRoot : AgentSystemEnhanceAction
}

sealed interface AgentMemoryAction {
    data object NavigateBack : AgentMemoryAction
    data class ToggleEnabled(val enabled: Boolean) : AgentMemoryAction
    data class DraftChanged(val content: String) : AgentMemoryAction
    /** 丢弃未保存草稿，恢复为已保存内容（不能复用 DraftChanged：它永远置脏）。 */
    data object DiscardDraft : AgentMemoryAction
    data object Save : AgentMemoryAction
    data object Clear : AgentMemoryAction
    data object DismissNotice : AgentMemoryAction
    /** 切换 long/short 记忆目标（丢弃未保存草稿并重新加载）。 */
    data class SwitchTarget(val target: MemoryTargetUi) : AgentMemoryAction
    /** 更新搜索关键词（仅过滤当前 draft 展示，不落盘）。 */
    data class SearchChanged(val query: String) : AgentMemoryAction
    /** 手动触发短期→长期 rollup 整理。 */
    data object Rollup : AgentMemoryAction
}
