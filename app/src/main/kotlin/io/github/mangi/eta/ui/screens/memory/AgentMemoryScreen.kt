package io.github.mangi.eta.ui.screens.memory

import io.github.mangi.eta.agent.memory.AgentMemoryPreview
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.History
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.components.EtaCard
import io.github.mangi.eta.ui.components.EtaPreference
import io.github.mangi.eta.ui.components.EtaPreferenceDefaults
import io.github.mangi.eta.ui.components.EtaPreferenceDivider
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
import io.github.mangi.eta.ui.components.EtaSwitchPreference
import io.github.mangi.eta.ui.components.EtaTextButton
import io.github.mangi.eta.ui.components.EtaWindowDialog
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffold
import io.github.mangi.eta.ui.layout.horizontalCutoutPadding
import io.github.mangi.eta.ui.markdown.MarkdownTone
import io.github.mangi.eta.ui.markdown.StaticMarkdown
import io.github.mangi.eta.ui.model.AgentMemoryAction
import io.github.mangi.eta.ui.model.AgentMemoryUiState
import io.github.mangi.eta.ui.model.MemoryTargetUi
import java.text.NumberFormat
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme

private enum class MemoryViewMode { Preview, Edit }

/**
 * 记忆页：顶部是开关与预算，下方 MEMORY.md 以“文档”形式占满剩余高度。
 * 默认渲染 Markdown 便于阅读，切到编辑后是无边框的整页编辑区；只有存在未保存修改时才浮出保存栏。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AgentMemoryScreen(
    state: AgentMemoryUiState,
    onAction: (AgentMemoryAction) -> Unit,
) {
    var showClearDialog by remember { mutableStateOf(false) }
    // P2（v3.11.28）：切换 long/short 目标若有未保存草稿，先弹确认再切换（防误触丢编辑）。
    var pendingTargetSwitch by remember { mutableStateOf<MemoryTargetUi?>(null) }
    // 「整理」会重新加载记忆文件并覆盖草稿（见 AgentMemoryStore.proposeRollup → refreshMemory），
    // 与「切换目标」同理：有未保存修改时必须先确认，否则一次误触就丢了编辑。
    var pendingRollup by remember { mutableStateOf(false) }
    // 历史快照：列表对话框 + 恢复二次确认。
    var showHistoryDialog by remember { mutableStateOf(false) }
    var pendingHistoryRestore by remember { mutableStateOf<String?>(null) }
    var mode by rememberSaveable { mutableStateOf<MemoryViewMode?>(null) }
    // 首次读到内容前不决定模式：空记忆直接进入编辑，已有内容先给出可读的预览。
    LaunchedEffect(state.isLoading) {
        if (!state.isLoading && mode == null) {
            mode = if (state.savedContent.isBlank()) MemoryViewMode.Edit else MemoryViewMode.Preview
        }
    }
    val currentMode = mode ?: MemoryViewMode.Preview
    val imeVisible = WindowInsets.isImeVisible

    MiuixScaffold(
        title = stringResource(R.string.ui_memory_b55ff5),
        onBack = { onAction(AgentMemoryAction.NavigateBack) },
        actions = {
            IconButton(
                onClick = { showHistoryDialog = true },
                enabled = !state.isLoading,
            ) {
                Icon(
                    imageVector = Icons.Rounded.History,
                    contentDescription = stringResource(R.string.memory_history_title),
                    tint = if (!state.isLoading) {
                        MiuixTheme.colorScheme.onSurface
                    } else {
                        MiuixTheme.colorScheme.disabledOnSurface
                    },
                )
            }
            IconButton(
                onClick = { showClearDialog = true },
                enabled = !state.isLoading && !state.isSaving && state.draft.isNotEmpty(),
            ) {
                Icon(
                    imageVector = Icons.Rounded.DeleteOutline,
                    contentDescription = stringResource(R.string.ui_clear_all_memory_a43bd3),
                    tint = if (!state.isLoading && state.draft.isNotEmpty()) {
                        MiuixTheme.colorScheme.onSurface
                    } else {
                        MiuixTheme.colorScheme.disabledOnSurface
                    },
                )
            }
        },
    ) { paddingValues, scrollBehavior, sidePadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .horizontalCutoutPadding()
                .padding(top = paddingValues.calculateTopPadding())
                .padding(horizontal = sidePadding)
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .imePadding()
                .navigationBarsPadding(),
        ) {
            // 键盘弹出时收起设置区，把有限高度全部让给正在编辑的文档。
            AnimatedVisibility(
                visible = !imeVisible,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                // **必须包一层 Column**：AnimatedVisibility 内部用 Layout 实现，
                // 多个子元素会被测量后放在同一位置（堆叠），
                // 结果设置卡片正好压在「长期/短期记忆」切换按钮上 —— 真机反馈「被挡住了」。
                Column {
                MemoryTargetSwitcher(
                    state = state,
                    onRequestSwitch = { target ->
                        // P2：有未保存草稿时先确认，避免误触切换丢编辑。
                        if (state.hasUnsavedChanges && target != state.target) {
                            pendingTargetSwitch = target
                        } else {
                            onAction(AgentMemoryAction.SwitchTarget(target))
                        }
                    },
                    onRollup = {
                        if (state.hasUnsavedChanges) pendingRollup = true
                        else onAction(AgentMemoryAction.Rollup)
                    },
                )
                // v3.13.3：不再折叠（去掉 OverviewHeader 展开/收起交互），
                // 设置区直接平铺；热力图已移除（无人使用）。
                MemorySettingsGroup(state = state, onAction = onAction)
                }
            }
            MemorySearchField(state = state, mode = currentMode, onModeChange = { mode = it }, onAction = onAction)
            MemoryDocumentCard(
                state = state,
                mode = currentMode,
                onModeChange = { mode = it },
                onDraftChange = { onAction(AgentMemoryAction.DraftChanged(it)) },
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = EtaPreferenceDefaults.SidePadding),
            )
            MemorySaveBar(
                state = state,
                onDiscard = { onAction(AgentMemoryAction.DiscardDraft) },
                onSave = { onAction(AgentMemoryAction.Save) },
            )
            Spacer(Modifier.height(12.dp))
        }
    }

    if (showClearDialog) {
        EtaWindowDialog(
            show = true,
            title = stringResource(R.string.ui_clear_all_memory_a43bd3),
            summary = stringResource(R.string.ui_the_entire_contents_of_memory_md_will_be_deleted_and_83a8ac),
            onDismissRequest = { showClearDialog = false },
        ) {
            MiuixDialogActions(
                confirmText = stringResource(R.string.memory_clear),
                destructive = true,
                confirmEnabled = !state.isSaving,
                onCancel = { showClearDialog = false },
                onConfirm = {
                    showClearDialog = false
                    onAction(AgentMemoryAction.Clear)
                },
            )
        }
    }

    // P2（v3.11.28）：切换 long/short 目标前有未保存草稿 → 确认后丢弃并切换。
    pendingTargetSwitch?.let { pending ->
        EtaWindowDialog(
            show = true,
            title = stringResource(R.string.memory_switch_target_title),
            summary = stringResource(
                R.string.memory_switch_target_summary,
                stringResource(
                    if (pending == MemoryTargetUi.Short) R.string.memory_target_short
                    else R.string.memory_target_long,
                ),
            ),
            onDismissRequest = { pendingTargetSwitch = null },
        ) {
            MiuixDialogActions(
                confirmText = stringResource(R.string.memory_discard_and_switch),
                destructive = true,
                confirmEnabled = !state.isSaving,
                onCancel = { pendingTargetSwitch = null },
                onConfirm = {
                    pendingTargetSwitch = null
                    onAction(AgentMemoryAction.SwitchTarget(pending))
                },
            )
        }
    }

    // 「整理」确认。4.0 修复：原先误嵌在上面切换目标的 let 块内，
    // 有未保存修改时点整理不弹窗（静默失效）、切目标时双对话框叠加。必须独立显隐。
    if (pendingRollup) {
        EtaWindowDialog(
            show = true,
            title = stringResource(R.string.memory_tidy_title),
            summary = stringResource(R.string.memory_tidy_summary),
            onDismissRequest = { pendingRollup = false },
        ) {
            MiuixDialogActions(
                confirmText = stringResource(R.string.memory_discard_and_tidy),
                destructive = true,
                confirmEnabled = !state.isRollingUp,
                onCancel = { pendingRollup = false },
                onConfirm = {
                    pendingRollup = false
                    onAction(AgentMemoryAction.Rollup)
                },
            )
        }
    }

    // 整理结果预览确认（S5）：LLM 提炼结果先过目，确认后才追加进长期记忆。
    state.pendingRollupResult?.let { result ->
        EtaWindowDialog(
            show = true,
            title = stringResource(R.string.memory_rollup_preview_title),
            summary = stringResource(R.string.memory_rollup_preview_summary),
            onDismissRequest = { onAction(AgentMemoryAction.DismissRollupResult) },
        ) {
            Column {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = result,
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                }
                Spacer(Modifier.height(8.dp))
                MiuixDialogActions(
                    confirmText = stringResource(R.string.memory_rollup_confirm),
                    cancelText = stringResource(R.string.memory_rollup_discard),
                    confirmEnabled = !state.isRollingUp,
                    onCancel = { onAction(AgentMemoryAction.DismissRollupResult) },
                    onConfirm = { onAction(AgentMemoryAction.CommitRollup) },
                )
            }
        }
    }

    // 历史快照列表：恢复前当前内容会自动存入历史，回滚也可回滚。
    if (showHistoryDialog) {
        EtaWindowDialog(
            show = true,
            title = stringResource(R.string.memory_history_title),
            summary = stringResource(R.string.memory_history_summary),
            onDismissRequest = { showHistoryDialog = false },
        ) {
            Column {
                if (state.history.isEmpty()) {
                    Text(
                        text = stringResource(R.string.memory_history_empty),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        state.history.forEach { entry ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = formatHistoryTime(entry.savedAtMillis),
                                        style = MiuixTheme.textStyles.body2,
                                        color = MiuixTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        text = stringResource(
                                            R.string.memory_history_entry_size,
                                            formatBytes(entry.byteSize),
                                        ),
                                        style = MiuixTheme.textStyles.footnote2,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    )
                                }
                                EtaTextButton(
                                    text = stringResource(R.string.memory_history_restore),
                                    onClick = {
                                        pendingHistoryRestore = entry.id
                                    },
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                EtaTextButton(
                    text = stringResource(R.string.ui_knew_cb63c6),
                    onClick = { showHistoryDialog = false },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    // 恢复历史二次确认：明确告知「当前内容先入历史」，回滚可逆。
    pendingHistoryRestore?.let { id ->
        EtaWindowDialog(
            show = true,
            title = stringResource(R.string.memory_history_confirm_title),
            summary = stringResource(R.string.memory_history_confirm_summary),
            onDismissRequest = { pendingHistoryRestore = null },
        ) {
            MiuixDialogActions(
                confirmText = stringResource(R.string.memory_history_restore),
                confirmEnabled = !state.isSaving,
                onCancel = { pendingHistoryRestore = null },
                onConfirm = {
                    pendingHistoryRestore = null
                    showHistoryDialog = false
                    onAction(AgentMemoryAction.RestoreHistory(id))
                },
            )
        }
    }

    state.notice?.let { notice ->
        EtaWindowDialog(
            show = true,
            title = stringResource(R.string.ui_memory_b55ff5),
            summary = notice,
            onDismissRequest = { onAction(AgentMemoryAction.DismissNotice) },
        ) {
            EtaTextButton(
                text = stringResource(R.string.ui_knew_cb63c6),
                onClick = { onAction(AgentMemoryAction.DismissNotice) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun MemoryTargetSwitcher(
    state: AgentMemoryUiState,
    onRequestSwitch: (MemoryTargetUi) -> Unit,
    onRollup: () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = EtaPreferenceDefaults.SidePadding)
            .padding(top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 与「预览 / 编辑」同一套视觉：外层容器 + 内层选中态。
        // 之前是两个独立的胶囊 chip，与页面其他控件不是一套语言（真机反馈"布局不行"）。
        Row(
            modifier = Modifier
                .squircleSurface(color = colors.surfaceContainerHigh, cornerRadius = 12.dp)
                .padding(3.dp),
        ) {
            listOf(MemoryTargetUi.Long, MemoryTargetUi.Short).forEach { target ->
                val selected = state.target == target
                val background by animateColorAsState(
                    if (selected) colors.surface else colors.surfaceContainerHigh,
                    label = "memory_target_tab",
                )
                Box(
                    modifier = Modifier
                        .squircleSurface(color = background, cornerRadius = 9.dp)
                        .clickable(enabled = !state.isLoading && !selected) {
                            onRequestSwitch(target)
                        }
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(
                            if (target == MemoryTargetUi.Long) R.string.memory_target_long
                            else R.string.memory_target_short,
                        ),
                        style = MiuixTheme.textStyles.footnote1,
                        fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                        color = if (selected) colors.onSurface else colors.onSurfaceVariantSummary,
                    )
                }
            }
        }
        Spacer(modifier = Modifier.weight(1f))
        if (state.target == MemoryTargetUi.Short) {
            // 与左侧分段控件同款 squircle 胶囊，两字「整理」；不加图形符号。
            // 忙碌态看 isRollingUp（整理进行中），不看 isSaving（保存与整理互相独立）。
            val busy = state.isLoading || state.isRollingUp
            Box(
                modifier = Modifier
                    .squircleSurface(
                        color = if (busy) {
                            colors.surfaceContainerHigh
                        } else {
                            colors.primary.copy(alpha = 0.12f)
                        },
                        cornerRadius = 12.dp,
                    )
                    .clickable(enabled = !busy, onClick = onRollup)
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(
                        if (state.isRollingUp) R.string.memory_tidying else R.string.memory_tidy,
                    ),
                    style = MiuixTheme.textStyles.footnote1,
                    color = if (busy) colors.onSurfaceVariantSummary else colors.primary,
                )
            }
        } else {
            Text(
                text = stringResource(R.string.memory_persistent_facts),
                style = MiuixTheme.textStyles.footnote2,
                color = colors.onSurfaceVariantSummary,
            )
        }
    }
}

@Composable
private fun MemorySettingsGroup(
    state: AgentMemoryUiState,
    onAction: (AgentMemoryAction) -> Unit,
) {
    EtaPreferenceGroup(
        modifier = Modifier
            .padding(horizontal = EtaPreferenceDefaults.SidePadding)
            .padding(top = 8.dp, bottom = EtaPreferenceDefaults.GroupSpacing),
    ) {
        EtaSwitchPreference(
            title = stringResource(R.string.ui_enable_memory_4b69b7),
            summary = stringResource(R.string.ui_after_closing_no_memory_will_be_injected_and_the_mod_db3d23),
            checked = state.enabled,
            enabled = !state.isLoading,
            onCheckedChange = { onAction(AgentMemoryAction.ToggleEnabled(it)) },
        )
        EtaPreferenceDivider(hasLeading = false)
        EtaPreference(
            title = stringResource(R.string.ui_core_memory_injection_budget_48b5d5),
            summary = stringResource(R.string.memory_budget_summary, formatNumber(state.coreBudgetChars)),
        )
        // U6：核心注入水位——真机曾出现「预算用到 89% 无人知晓」，把占用做成一眼可见。
        MemoryCoreUsageBar(state = state)
    }
}

/** 核心区注入水位条：已用/预算字符，>80% 转警示色，超预算时明确提示「模型看不到」。 */
@Composable
private fun MemoryCoreUsageBar(state: AgentMemoryUiState) {
    val colors = MiuixTheme.colorScheme
    val fraction = if (state.coreBudgetChars <= 0) {
        0f
    } else {
        (state.coreUsedChars.toFloat() / state.coreBudgetChars).coerceIn(0f, 1f)
    }
    val warning = state.coreTruncated || state.coreUsagePercent >= 80
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.memory_core_usage),
                style = MiuixTheme.textStyles.footnote2,
                color = colors.onSurfaceVariantSummary,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(
                    R.string.memory_core_usage_value,
                    formatNumber(state.coreUsedChars),
                    formatNumber(state.coreBudgetChars),
                    state.coreUsagePercent,
                ),
                style = MiuixTheme.textStyles.footnote2,
                fontWeight = FontWeight.Medium,
                color = if (warning) colors.error else colors.onSurface,
            )
        }
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .background(colors.surfaceContainerHigh, CircleShape),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(4.dp)
                    .background(
                        if (warning) colors.error else colors.primary,
                        CircleShape,
                    ),
            )
        }
        if (state.coreTruncated) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.memory_core_truncated_hint),
                style = MiuixTheme.textStyles.footnote2,
                color = colors.error,
            )
        }
    }
}

/** 记忆搜索框：仅过滤当前 draft 的展示，不落盘（spec memory-upgrade §3.3）。 */
@Composable
private fun MemorySearchField(
    state: AgentMemoryUiState,
    mode: MemoryViewMode,
    onModeChange: (MemoryViewMode) -> Unit,
    onAction: (AgentMemoryAction) -> Unit,
) {
    top.yukonga.miuix.kmp.basic.TextField(
        value = state.searchQuery,
        onValueChange = { text ->
            onAction(AgentMemoryAction.SearchChanged(text))
            // 编辑区是输入框、不能过滤正文：输入搜索词时自动切到预览展示命中结果
            //（草稿保留、dirty 不变，可切回继续编辑）——否则搜索在编辑模式下是死区。
            if (text.isNotBlank() && mode == MemoryViewMode.Edit) {
                onModeChange(MemoryViewMode.Preview)
            }
        },
        label = stringResource(R.string.memory_search_label),
        useLabelAsPlaceholder = true,
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = EtaPreferenceDefaults.SidePadding)
            // 上下都要留白：只留 top 会与下面的 MEMORY.md 卡片粘连（真机反馈）。
            .padding(
                top = EtaPreferenceDefaults.GroupSpacing,
                bottom = EtaPreferenceDefaults.GroupSpacing,
            ),
    )
}

/** 在任意文本上做「保留命中行 + 折叠其余」的过滤：搜索时定位命中行，其余行省略。 */
private fun filterLines(text: String, query: String, gapLabel: (Int) -> String): String {
    val lines = text.split('\n')
    val matched = lines.indices.filter { lines[it].contains(query, ignoreCase = true) }
    if (matched.isEmpty()) return ""
    return buildString {
        var last = -1
        matched.forEach { index ->
            if (last >= 0 && index > last + 1) append("\n" + gapLabel(index - last - 1) + "\n")
            append(lines[index])
            if (index != matched.last()) append('\n')
            last = index
        }
    }
}

/** 文档卡片：标题栏承载文件名、体积与预览/编辑切换，正文区占满卡片剩余高度并独立滚动。 */
@Composable
private fun MemoryDocumentCard(
    state: AgentMemoryUiState,
    mode: MemoryViewMode,
    onModeChange: (MemoryViewMode) -> Unit,
    onDraftChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colorScheme
    val overLimit = state.draftBytes > state.maxBytes
    // U5：大纲直达——1-3 级标题 chips，点选只看该节（含归档区），「全部」还原。
    var outlineSection by remember { mutableStateOf<String?>(null) }
    val outline = remember(state.draft) { AgentMemoryPreview.headings(state.draft) }
    val searching = state.searchQuery.isNotBlank()
    EtaCard(modifier = modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 10.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.Notes,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = colors.primary,
            )
            Spacer(Modifier.width(8.dp))
            // v3.13.3：色点一眼区分长期/短期（长期=主色，短期=主色容器淡色），辅助标题文字。
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(
                        if (state.target == MemoryTargetUi.Short) colors.primaryContainer else colors.primary,
                        CircleShape,
                    ),
            )
            Spacer(Modifier.width(6.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    // P1（v3.11.28）：按 target 区分标题，避免用户分不清编辑的是长期还是短期文件。
                    text = if (state.target == MemoryTargetUi.Short) {
                        stringResource(R.string.memory_file_short_term)
                    } else {
                        "MEMORY.md"
                    },
                    style = MiuixTheme.textStyles.body2,
                    fontWeight = FontWeight.Medium,
                    color = colors.onSurface,
                )
                Text(
                    text = "${formatBytes(state.draftBytes)} / ${formatBytes(state.maxBytes)}",
                    style = MiuixTheme.textStyles.footnote2,
                    color = if (overLimit) colors.error else colors.onSurfaceVariantSummary,
                )
            }
            MemoryModeSwitch(mode = mode, enabled = !state.isLoading, onModeChange = onModeChange)
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(colors.dividerLine),
        )
        // 大纲 chips：预览模式且无搜索时显示（编辑/搜索时正文另有呈现方式）。
        if (outline.isNotEmpty() && mode == MemoryViewMode.Preview && !searching) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MemoryOutlineChip(
                    label = stringResource(R.string.memory_outline_all),
                    selected = outlineSection == null,
                    onClick = { outlineSection = null },
                )
                outline.forEach { heading ->
                    MemoryOutlineChip(
                        label = heading.trimStart('#').trim(),
                        selected = outlineSection == heading,
                        onClick = {
                            outlineSection = if (outlineSection == heading) null else heading
                        },
                    )
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(0.5.dp)
                    .background(colors.dividerLine),
            )
        }
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            when {
                state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    InfiniteProgressIndicator(size = 24.dp)
                }
                else -> Crossfade(targetState = mode, label = "memory_mode") { current ->
                    when (current) {
                        MemoryViewMode.Preview -> MemoryPreview(
                            content = state.draft,
                            query = state.searchQuery,
                            sectionFilter = outlineSection,
                            onStartEditing = { onModeChange(MemoryViewMode.Edit) },
                        )
                        MemoryViewMode.Edit -> MemoryEditor(
                            value = state.draft,
                            enabled = !state.isSaving,
                            onValueChange = onDraftChange,
                        )
                    }
                }
            }
        }
    }
}

/** 大纲 chip：选中态用主色淡底，与「长期/短期」分段控件同一视觉语言。 */
@Composable
private fun MemoryOutlineChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    Box(
        modifier = Modifier
            .squircleSurface(
                color = if (selected) colors.primary.copy(alpha = 0.12f) else colors.surfaceContainerHigh,
                cornerRadius = 9.dp,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.footnote2,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = if (selected) colors.primary else colors.onSurfaceVariantSummary,
            maxLines = 1,
        )
    }
}

@Composable
private fun MemoryPreview(
    content: String,
    query: String,
    sectionFilter: String?,
    onStartEditing: () -> Unit,
) {
    // **默认只渲染核心区**：整个 MEMORY.md 有 17 万字符（242KB），
    // 全塞进一个可滚动 Text 会让 Compose 全量测量布局 —— 进页面白屏半秒以上。
    // 核心区是每轮真正注入模型的部分（默认 32K 预算），也是用户最常看的；
    // 归档区改走大纲 chips 直达（U5），不再只有「进编辑看」一条路。
    val slice = remember(content) { AgentMemoryPreview.slice(content) }
    if (content.isBlank()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .clickable(onClick = onStartEditing)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(R.string.memory_empty_title),
                style = MiuixTheme.textStyles.body1,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.ui_core_memory_user_name_long_term_preferences_aa6ff9),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        return
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        val trimmedQuery = query.trim()
        // 搜索时过滤**全文**（不限于核心区切片）：归档区内容也应能定位命中。
        // 输出只保留命中行、数量很少，渲染量小；无搜索时优先按大纲选中节渲染，
        // 其次是核心区切片（防全量布局白屏）。
        // 模板走 stringResource（配置变化会重组），占位符在非 composable 里用 String.format 填，
        // 不用 LocalContext.getString —— 那样 lint 会判 LocalContextGetResourceValueCall。
        val gapsTemplate = stringResource(R.string.memory_search_gaps)
        val display = remember(content, trimmedQuery, gapsTemplate, sectionFilter) {
            when {
                trimmedQuery.isNotEmpty() -> filterLines(content, trimmedQuery) { n -> gapsTemplate.format(n) }
                sectionFilter != null -> AgentMemoryPreview.section(content, sectionFilter) ?: slice.text
                else -> slice.text
            }
        }
        if (trimmedQuery.isNotEmpty()) {
            // U4：命中计数 + 匹配加粗高亮，让用户知道「有多少、在哪」。
            val hitCount = remember(content, trimmedQuery) { countMatches(content, trimmedQuery) }
            Text(
                text = stringResource(R.string.memory_search_hits, hitCount),
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
        }
        if (trimmedQuery.isNotEmpty() && display.isBlank()) {
            // 无命中时给明确反馈，而不是整页空白（用户感知为"没反应"）。
            Text(
                text = stringResource(R.string.memory_search_empty, trimmedQuery),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            val highlighted = remember(display, trimmedQuery) { highlightMatches(display, trimmedQuery) }
            StaticMarkdown(content = highlighted, tone = MarkdownTone.Answer, modifier = Modifier.fillMaxWidth())
        }
        if (slice.truncated && trimmedQuery.isEmpty() && sectionFilter == null) {
            Spacer(Modifier.height(14.dp))
            Text(
                text = stringResource(R.string.memory_archive_hint, slice.archivedChars / 1024),
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** 全文命中次数（搜索计数用）。 */
private fun countMatches(text: String, query: String): Int {
    if (query.isEmpty()) return 0
    var count = 0
    var index = 0
    while (true) {
        index = text.indexOf(query, index, ignoreCase = true)
        if (index < 0) break
        count++
        index += query.length
    }
    return count
}

/**
 * 把匹配文本包成 Markdown 粗体（`**…**`）做高亮。
 * query 含 Markdown 控制字符时不高亮（避免把预览渲染搞乱），命中计数不受影响。
 */
private fun highlightMatches(text: String, query: String): String {
    if (query.isBlank()) return text
    if (query.any { it in "*_`[]\\" }) return text
    return buildString(text.length + query.length * 4) {
        var index = 0
        while (true) {
            val hit = text.indexOf(query, index, ignoreCase = true)
            if (hit < 0) {
                append(text, index, text.length)
                break
            }
            append(text, index, hit)
            append("**")
            append(text, hit, hit + query.length)
            append("**")
            index = hit + query.length
        }
    }
}

/** 无边框整页编辑：与卡片同色，不再嵌一层灰色输入框；点到空白处也能把光标落进正文。 */
@Composable
private fun MemoryEditor(
    value: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    val focusRequester = remember { FocusRequester() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(interactionSource = null, indication = null) { focusRequester.requestFocus() }
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        if (value.isEmpty()) {
            Text(
                text = stringResource(R.string.ui_core_memory_user_name_long_term_preferences_aa6ff9),
                style = MemoryTextStyle(),
                color = colors.onSurfaceVariantSummary,
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            textStyle = MemoryTextStyle().copy(color = colors.onSurface),
            cursorBrush = SolidColor(colors.primary),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester),
        )
    }
}

@Composable
private fun MemoryTextStyle() = MiuixTheme.textStyles.body2.copy(lineHeight = 22.sp)

/** 轻量分段切换：两个选项常驻可见，选中项用浮起的胶囊表示，比整行 TabRow 更贴合卡片标题栏。 */
@Composable
private fun MemoryModeSwitch(
    mode: MemoryViewMode,
    enabled: Boolean,
    onModeChange: (MemoryViewMode) -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    Row(
        modifier = Modifier
            .squircleSurface(color = colors.surfaceContainerHigh, cornerRadius = 12.dp)
            .padding(3.dp),
    ) {
        MemoryViewMode.entries.forEach { option ->
            val selected = option == mode
            val background by animateColorAsState(
                if (selected) colors.surface else colors.surfaceContainerHigh,
                label = "memory_mode_tab",
            )
            Box(
                modifier = Modifier
                    .squircleSurface(color = background, cornerRadius = 9.dp)
                    .clickable(enabled = enabled && !selected) { onModeChange(option) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(
                        if (option == MemoryViewMode.Preview) R.string.memory_mode_preview else R.string.memory_mode_edit,
                    ),
                    style = MiuixTheme.textStyles.footnote1,
                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                    color = if (selected) colors.onSurface else colors.onSurfaceVariantSummary,
                )
            }
        }
    }
}

/** 有未保存修改或超限时才出现：左侧说明状态，右侧放弃与保存，避免常驻一个灰掉的保存按钮。 */
@Composable
private fun MemorySaveBar(
    state: AgentMemoryUiState,
    onDiscard: () -> Unit,
    onSave: () -> Unit,
) {
    val overLimit = state.draftBytes > state.maxBytes
    AnimatedVisibility(
        visible = !state.isLoading && (state.hasUnsavedChanges || state.isSaving),
        enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
        exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = EtaPreferenceDefaults.SidePadding)
                .padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(
                            if (overLimit) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.primary,
                            CircleShape,
                        ),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(if (overLimit) R.string.memory_over_limit else R.string.memory_unsaved_changes),
                    style = MiuixTheme.textStyles.footnote1,
                    color = if (overLimit) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 2,
                )
            }
            EtaTextButton(
                text = stringResource(R.string.memory_discard),
                enabled = !state.isSaving,
                onClick = onDiscard,
                minHeight = 40.dp,
                cornerRadius = 20.dp,
                insideMargin = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            )
            EtaTextButton(
                text = stringResource(if (state.isSaving) R.string.memory_saving else R.string.memory_save),
                enabled = state.canSave,
                onClick = onSave,
                minHeight = 40.dp,
                cornerRadius = 20.dp,
                insideMargin = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

private fun formatBytes(bytes: Int): String = when {
    bytes < 1_024 -> "$bytes B"
    bytes < 1_024 * 1_024 -> "%.1f KiB".format(bytes / 1_024.0)
    bytes % (1_024 * 1_024) == 0 -> "${bytes / (1_024 * 1_024)} MiB"
    else -> "%.2f MiB".format(bytes / (1_024.0 * 1_024.0))
}

private fun formatNumber(value: Int): String = NumberFormat.getIntegerInstance().format(value)

/** 历史快照时间展示：本地化的中等日期 + 短时间（如 2026/10/22 00:53）。 */
private fun formatHistoryTime(savedAtMillis: Long): String =
    java.text.DateFormat.getDateTimeInstance(
        java.text.DateFormat.MEDIUM,
        java.text.DateFormat.SHORT,
    ).format(java.util.Date(savedAtMillis))
