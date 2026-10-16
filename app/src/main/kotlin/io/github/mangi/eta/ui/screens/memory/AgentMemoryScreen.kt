package io.github.mangi.eta.ui.screens.memory

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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.DeleteOutline
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
import io.github.mangi.eta.ui.components.ConversationActivityDay
import io.github.mangi.eta.ui.components.ConversationHeatmap
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
                    onRollup = { onAction(AgentMemoryAction.Rollup) },
                )
                MemorySettingsGroup(state = state, onAction = onAction)
            }
            MemoryHeatmapSection()
            MemorySearchField(state = state, onAction = onAction)
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
                onDiscard = { onAction(AgentMemoryAction.DraftChanged(state.savedContent)) },
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
            title = "切换记忆目标",
            summary = "当前有未保存的修改，切换会丢弃这些修改并载入${if (pending == MemoryTargetUi.Short) "短期" else "长期"}记忆。",
            onDismissRequest = { pendingTargetSwitch = null },
        ) {
            MiuixDialogActions(
                confirmText = "丢弃并切换",
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
        TargetChip(
            text = "长期记忆",
            selected = state.target == MemoryTargetUi.Long,
            enabled = !state.isLoading,
            onClick = { onRequestSwitch(MemoryTargetUi.Long) },
        )
        Spacer(modifier = Modifier.width(8.dp))
        TargetChip(
            text = "短期记忆",
            selected = state.target == MemoryTargetUi.Short,
            enabled = !state.isLoading,
            onClick = { onRequestSwitch(MemoryTargetUi.Short) },
        )
        Spacer(modifier = Modifier.weight(1f))
        if (state.target == MemoryTargetUi.Short) {
            EtaTextButton(
                text = if (state.isSaving) "整理中…" else "整理到长期",
                enabled = !state.isLoading && !state.isSaving,
                onClick = onRollup,
            )
        } else {
            Text(
                text = "跨会话持久事实",
                style = MiuixTheme.textStyles.footnote2,
                color = colors.onSurfaceVariantSummary,
            )
        }
    }
}

@Composable
private fun TargetChip(
    text: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    Box(
        modifier = Modifier
            .background(
                color = if (selected) colors.primary.copy(alpha = 0.15f) else colors.surfaceContainerHigh,
                shape = CircleShape,
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Text(
            text = text,
            style = MiuixTheme.textStyles.body2,
            color = if (selected) colors.primary else colors.onSurface,
        )
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
    }
}

/** 会话活跃度热力图（spec memory-upgrade §3.4）：只读展示，数据从会话表按天聚合。 */
@Composable
private fun MemoryHeatmapSection() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var days by remember { mutableStateOf<List<ConversationActivityDay>?>(null) }
    LaunchedEffect(Unit) {
        days = runCatching {
            val snapshot = io.github.mangi.eta.ui.app.AgentConversationStore.load(context)
            snapshot.updatedAt
                .mapNotNull { (_, millis) -> millis.takeIf { it > 0 }?.let { java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).toLocalDate() } }
                .groupingBy { it }
                .eachCount()
                .map { (date, count) -> ConversationActivityDay(date, count) }
        }.getOrNull()
    }
    val loaded = days ?: return
    ConversationHeatmap(
        days = loaded,
        modifier = Modifier
            .padding(horizontal = EtaPreferenceDefaults.SidePadding)
            .padding(top = EtaPreferenceDefaults.GroupSpacing),
    )
}

/** 记忆搜索框：仅过滤当前 draft 的展示，不落盘（spec memory-upgrade §3.3）。 */
@Composable
private fun MemorySearchField(
    state: AgentMemoryUiState,
    onAction: (AgentMemoryAction) -> Unit,
) {
    top.yukonga.miuix.kmp.basic.TextField(
        value = state.searchQuery,
        onValueChange = { onAction(AgentMemoryAction.SearchChanged(it)) },
        label = "搜索记忆",
        useLabelAsPlaceholder = true,
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = EtaPreferenceDefaults.SidePadding)
            .padding(top = EtaPreferenceDefaults.GroupSpacing),
    )
}

/** 搜索过滤后的文档内容：命中行保留，其余行折叠为省略号，便于快速定位。 */
private fun filteredDraft(state: AgentMemoryUiState): String {
    val query = state.searchQuery.trim()
    if (query.isEmpty()) return state.draft
    val lines = state.draft.split('\n')
    val matched = lines.indices.filter { lines[it].contains(query, ignoreCase = true) }
    if (matched.isEmpty()) return ""
    return buildString {
        var last = -1
        matched.forEach { index ->
            if (last >= 0 && index > last + 1) append("\n…（中间 ${index - last - 1} 行已过滤）…\n")
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
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    // P1（v3.11.28）：按 target 区分标题，避免用户分不清编辑的是长期还是短期文件。
                    text = if (state.target == MemoryTargetUi.Short) "MEMORY.md（短期）" else "MEMORY.md",
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
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            when {
                state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    InfiniteProgressIndicator(size = 24.dp)
                }
                else -> Crossfade(targetState = mode, label = "memory_mode") { current ->
                    when (current) {
                        MemoryViewMode.Preview -> MemoryPreview(
                            content = if (state.searchQuery.isBlank()) state.draft else filteredDraft(state),
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

@Composable
private fun MemoryPreview(content: String, onStartEditing: () -> Unit) {
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
    Box(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        StaticMarkdown(content = content, tone = MarkdownTone.Answer, modifier = Modifier.fillMaxWidth())
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
