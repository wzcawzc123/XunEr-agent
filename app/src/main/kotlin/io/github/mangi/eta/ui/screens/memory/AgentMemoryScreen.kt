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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.itos.coui.layout.CouiPageDefaults
import com.itos.coui.layout.Divider
import com.itos.coui.layout.Group
import com.itos.coui.scroll.rememberCouiOverscrollEffect
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.memory.AgentMemorySearch
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffold
import io.github.mangi.eta.ui.components.coui.CouiButton
import io.github.mangi.eta.ui.components.coui.CouiButtonRole
import io.github.mangi.eta.ui.components.coui.CouiConfirmDialog
import io.github.mangi.eta.ui.components.coui.CouiNoticeDialog
import io.github.mangi.eta.ui.components.coui.CouiPreference
import io.github.mangi.eta.ui.components.coui.CouiScaffold
import io.github.mangi.eta.ui.components.coui.CouiSwitchPreference
import io.github.mangi.eta.ui.components.coui.CouiTopAppBarIconButton
import io.github.mangi.eta.ui.layout.horizontalCutoutPadding
import io.github.mangi.eta.ui.markdown.LazyStaticMarkdown
import io.github.mangi.eta.ui.markdown.MarkdownTone
import io.github.mangi.eta.ui.model.AgentMemoryAction
import io.github.mangi.eta.ui.model.AgentMemoryUiState
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

private enum class MemoryViewMode { Preview, Edit }

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AgentMemoryScreen(
    state: AgentMemoryUiState,
    onAction: (AgentMemoryAction) -> Unit,
) {
    var showClearDialog by remember { mutableStateOf(false) }
    // 记忆 v2 写前快照：给一条显式回滚入口，模型改坏记忆时不必靠整机备份。
    var showHistoryDialog by remember { mutableStateOf(false) }
    // 搜索是纯展示态（不落盘、不影响草稿），留在页面内即可，不必经 Store/Action 绕一圈。
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var mode by rememberSaveable {
        mutableStateOf(
            if (!state.isLoading && state.savedContent.isBlank()) MemoryViewMode.Edit else MemoryViewMode.Preview,
        )
    }
    var initialModeResolved by rememberSaveable { mutableStateOf(!state.isLoading) }
    LaunchedEffect(state.isLoading) {
        if (!state.isLoading && !initialModeResolved) {
            initialModeResolved = true
            mode = if (state.savedContent.isBlank()) MemoryViewMode.Edit else MemoryViewMode.Preview
        }
    }
    val currentMode = mode
    val imeVisible = WindowInsets.isImeVisible
    val canClear = !state.isLoading && !state.isSaving && state.draft.isNotEmpty()

    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            CouiScaffold(
                title = stringResource(R.string.ui_memory_b55ff5),
                onBack = { onAction(AgentMemoryAction.NavigateBack) },
                compactTitle = true,
                actions = {
                    CouiTopAppBarIconButton(
                        icon = Icons.Rounded.DeleteOutline,
                        contentDescription = stringResource(R.string.ui_clear_all_memory_a43bd3),
                        enabled = canClear,
                        onClick = { showClearDialog = true },
                    )
                },
            ) { contentPadding, _, nestedScrollConnection, sidePadding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .horizontalCutoutPadding()
                        .padding(top = contentPadding.calculateTopPadding())
                        .padding(horizontal = sidePadding)
                        .then(if (nestedScrollConnection != null) Modifier.nestedScroll(nestedScrollConnection) else Modifier)
                        .imePadding()
                        .navigationBarsPadding(),
                ) {
                    AnimatedVisibility(
                        visible = !imeVisible,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically(),
                    ) {
                        MemorySettingsGroup(state = state, onAction = onAction, onShowHistory = { showHistoryDialog = true })
                    }
                    MemoryDocumentCard(
                        state = state,
                        mode = currentMode,
                        searchQuery = searchQuery,
                        onSearchChange = { query ->
                            searchQuery = query
                            // 编辑区是输入框、没法过滤正文：输入搜索词时自动切预览看命中，
                            // 否则搜索在编辑模式下是死区（草稿保留，可切回继续编辑）。
                            if (query.isNotBlank() && currentMode == MemoryViewMode.Edit) {
                                mode = MemoryViewMode.Preview
                            }
                        },
                        onModeChange = { mode = it },
                        onDraftChange = { onAction(AgentMemoryAction.DraftChanged(it)) },
                        modifier = Modifier
                            .weight(1f)
                            .padding(top = CouiPageDefaults.groupSpacing)
                            .padding(horizontal = CouiPageDefaults.horizontalMargin),
                    )
                    MemorySaveBar(
                        state = state,
                        onDiscard = { onAction(AgentMemoryAction.DraftChanged(state.savedContent)) },
                        onSave = { onAction(AgentMemoryAction.Save) },
                    )
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
        AppearanceUiStyle.MIUIX -> {
            MiuixScaffold(
                title = stringResource(R.string.ui_memory_b55ff5),
                onBack = { onAction(AgentMemoryAction.NavigateBack) },
                actions = {
                    IconButton(
                        onClick = { showClearDialog = true },
                        enabled = canClear,
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
                    AnimatedVisibility(
                        visible = !imeVisible,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically(),
                    ) {
                        MemorySettingsGroup(state = state, onAction = onAction, onShowHistory = { showHistoryDialog = true })
                    }
                    MemoryDocumentCard(
                        state = state,
                        mode = currentMode,
                        searchQuery = searchQuery,
                        onSearchChange = { query ->
                            searchQuery = query
                            // 编辑区是输入框、没法过滤正文：输入搜索词时自动切预览看命中，
                            // 否则搜索在编辑模式下是死区（草稿保留，可切回继续编辑）。
                            if (query.isNotBlank() && currentMode == MemoryViewMode.Edit) {
                                mode = MemoryViewMode.Preview
                            }
                        },
                        onModeChange = { mode = it },
                        onDraftChange = { onAction(AgentMemoryAction.DraftChanged(it)) },
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 12.dp),
                    )
                    MemorySaveBar(
                        state = state,
                        onDiscard = { onAction(AgentMemoryAction.DraftChanged(state.savedContent)) },
                        onSave = { onAction(AgentMemoryAction.Save) },
                    )
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    }

    if (showClearDialog) {
        val onConfirmClear: () -> Unit = {
            showClearDialog = false
            onAction(AgentMemoryAction.Clear)
        }
        when (LocalUiStyle.current) {
            AppearanceUiStyle.COUI -> {
                CouiConfirmDialog(
                    visible = true,
                    title = stringResource(R.string.ui_clear_all_memory_a43bd3),
                    message = stringResource(R.string.ui_the_entire_contents_of_memory_md_will_be_deleted_and_83a8ac),
                    confirmText = stringResource(R.string.memory_clear),
                    confirmEnabled = !state.isSaving,
                    destructive = true,
                    onConfirm = onConfirmClear,
                    onDismissRequest = { showClearDialog = false },
                )
            }
            AppearanceUiStyle.MIUIX -> {
                WindowDialog(
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
                        onConfirm = onConfirmClear,
                    )
                }
            }
        }
    }

    state.notice?.let { notice ->
        when (LocalUiStyle.current) {
            AppearanceUiStyle.COUI -> {
                CouiNoticeDialog(
                    visible = true,
                    title = stringResource(R.string.ui_memory_b55ff5),
                    message = notice,
                    confirmText = stringResource(R.string.ui_knew_cb63c6),
                    onDismissRequest = { onAction(AgentMemoryAction.DismissNotice) },
                )
            }
            AppearanceUiStyle.MIUIX -> {
                WindowDialog(
                    show = true,
                    title = stringResource(R.string.ui_memory_b55ff5),
                    summary = notice,
                    onDismissRequest = { onAction(AgentMemoryAction.DismissNotice) },
                ) {
                    TextButton(
                        text = stringResource(R.string.ui_knew_cb63c6),
                        onClick = { onAction(AgentMemoryAction.DismissNotice) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }

    if (showHistoryDialog) {
        // 两套样式共用 Miuix 弹窗：COUI 的 CouiNoticeDialog 只能摆纯文本，
        // 承载不了「每条快照一个恢复按钮」的列表。
        WindowDialog(
            show = true,
            title = stringResource(R.string.memory_history_title),
            summary = stringResource(R.string.memory_history_note),
            onDismissRequest = { showHistoryDialog = false },
        ) {
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
                                text = formatTimestamp(entry.savedAtMillis),
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "${formatBytes(entry.byteSize)} · ${entry.revisionPrefix}",
                                style = MiuixTheme.textStyles.footnote2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                        TextButton(
                            text = stringResource(R.string.memory_history_restore),
                            enabled = !state.isSaving,
                            onClick = {
                                showHistoryDialog = false
                                onAction(AgentMemoryAction.RestoreHistory(entry.id))
                            },
                            minHeight = 36.dp,
                            cornerRadius = 18.dp,
                            insideMargin = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                        )
                    }
                }
            }
            TextButton(
                text = stringResource(R.string.action_close),
                onClick = { showHistoryDialog = false },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun MemorySettingsGroup(
    state: AgentMemoryUiState,
    onAction: (AgentMemoryAction) -> Unit,
    onShowHistory: () -> Unit,
) {
    val historyText = if (state.history.isEmpty()) {
        stringResource(R.string.memory_history_empty)
    } else {
        state.history.size.toString()
    }
    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            Group {
                CouiSwitchPreference(
                    title = stringResource(R.string.ui_enable_memory_4b69b7),
                    summary = stringResource(R.string.ui_after_closing_no_memory_will_be_injected_and_the_mod_db3d23),
                    checked = state.enabled,
                    enabled = !state.isLoading,
                    onCheckedChange = { onAction(AgentMemoryAction.ToggleEnabled(it)) },
                )
                Divider()
                CouiPreference(
                    title = stringResource(R.string.ui_core_memory_injection_budget_48b5d5),
                    summary = stringResource(R.string.memory_budget_summary, formatNumber(state.coreBudgetChars)),
                    showChevron = false,
                )
                Divider()
                CouiPreference(
                    title = stringResource(R.string.memory_history_title),
                    summary = historyText,
                    enabled = !state.isLoading && state.history.isNotEmpty(),
                    onClick = onShowHistory,
                )
            }
        }
        AppearanceUiStyle.MIUIX -> {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(top = 8.dp, bottom = 12.dp),
            ) {
                SwitchPreference(
                    title = stringResource(R.string.ui_enable_memory_4b69b7),
                    summary = stringResource(R.string.ui_after_closing_no_memory_will_be_injected_and_the_mod_db3d23),
                    checked = state.enabled,
                    enabled = !state.isLoading,
                    onCheckedChange = { onAction(AgentMemoryAction.ToggleEnabled(it)) },
                )
                BasicComponent(
                    title = stringResource(R.string.ui_core_memory_injection_budget_48b5d5),
                    summary = stringResource(R.string.memory_budget_summary, formatNumber(state.coreBudgetChars)),
                )
                ArrowPreference(
                    title = stringResource(R.string.memory_history_title),
                    summary = historyText,
                    enabled = !state.isLoading && state.history.isNotEmpty(),
                    onClick = onShowHistory,
                )
            }
        }
    }
}

@Composable
private fun MemoryDocumentCard(
    state: AgentMemoryUiState,
    mode: MemoryViewMode,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    onModeChange: (MemoryViewMode) -> Unit,
    onDraftChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colorScheme
    val isCoui = LocalUiStyle.current == AppearanceUiStyle.COUI
    val overLimit = state.draftBytes > state.maxBytes
    val cardContent: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = 16.dp,
                    end = if (isCoui) CouiPageDefaults.rowPadding else 10.dp,
                    top = 12.dp,
                    bottom = 12.dp,
                ),
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
                    text = "MEMORY.md",
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
        if (isCoui) {
            Divider()
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(0.5.dp)
                    .background(colors.dividerLine),
            )
        }
        // 搜索只在预览态出现：编辑区是输入框，过滤正文会把正在编辑的内容「藏起来」。
        if (mode == MemoryViewMode.Preview && !state.isLoading) {
            MemorySearchField(query = searchQuery, onQueryChange = onSearchChange)
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
                            query = searchQuery,
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

    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            val pageColors = CouiPageDefaults.themeColors()
            Column(
                modifier = modifier
                    .fillMaxWidth()
                    .clip(CouiPageDefaults.shape)
                    .background(pageColors.card),
            ) {
                cardContent()
            }
        }
        AppearanceUiStyle.MIUIX -> {
            Card(modifier = modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
                cardContent()
            }
        }
    }
}

/**
 * 记忆搜索框。
 */
@Composable
private fun MemorySearchField(
    query: String,
    onQueryChange: (String) -> Unit,
) {
    TextField(
        value = query,
        onValueChange = onQueryChange,
        label = stringResource(R.string.memory_search_label),
        useLabelAsPlaceholder = true,
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

/**
 * 搜索结果：纯文本逐行渲染，不走 Markdown。
 *
 * 命中行脱离上下文后，标题/列表/代码块的结构已被切断，交给 Markdown 渲染可能得到
 * 与原文完全不同的结果；高亮也不必往原文插星号（那样会破坏语法）。行号还能让用户
 * 切到编辑后自己定位。
 */
@Composable
private fun MemorySearchResults(content: String, query: String) {
    val result = remember(content, query) { AgentMemorySearch.search(content, query) }
    if (result.isEmpty) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(R.string.memory_search_empty, query),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        return
    }
    val scheme = MiuixTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(
            text = stringResource(R.string.memory_search_hits, result.total, result.matchedLines),
            style = MiuixTheme.textStyles.footnote2,
            color = scheme.onSurfaceVariantSummary,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        result.hits.forEach { hit ->
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Text(
                    text = hit.line.toString(),
                    style = MiuixTheme.textStyles.footnote2,
                    color = scheme.onSurfaceVariantSummary,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(38.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = highlightHit(hit, scheme.primary),
                    style = MiuixTheme.textStyles.body2,
                    color = scheme.onSurface,
                )
            }
        }
        if (result.truncated) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.memory_search_truncated, result.hits.size, result.matchedLines),
                style = MiuixTheme.textStyles.footnote2,
                color = scheme.onSurfaceVariantSummary,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** 命中片段加粗着色（区间由 AgentMemorySearch 算好，这里只负责拼样式）。 */
private fun highlightHit(hit: AgentMemorySearch.Hit, color: androidx.compose.ui.graphics.Color) =
    buildAnnotatedString {
        var cursor = 0
        for (range in hit.ranges) {
            if (range.first > cursor) append(hit.text, cursor, range.first)
            withStyle(SpanStyle(color = color, fontWeight = FontWeight.SemiBold)) {
                append(hit.text, range.first, range.last + 1)
            }
            cursor = range.last + 1
        }
        if (cursor < hit.text.length) append(hit.text, cursor, hit.text.length)
    }

@Composable
private fun MemoryPreview(content: String, query: String, onStartEditing: () -> Unit) {
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
    // 搜索优先：命中行自成一套渲染，不参与下面的全文虚拟化。
    val trimmedQuery = query.trim()
    if (trimmedQuery.isNotEmpty()) {
        MemorySearchResults(content = content, query = trimmedQuery)
        return
    }
    val overscroll = if (LocalUiStyle.current == AppearanceUiStyle.COUI) {
        rememberCouiOverscrollEffect()
    } else {
        null
    }
    LazyStaticMarkdown(
        content = content,
        tone = MarkdownTone.Answer,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        overscrollEffect = overscroll,
    )
}

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

@Composable
private fun MemorySaveBar(
    state: AgentMemoryUiState,
    onDiscard: () -> Unit,
    onSave: () -> Unit,
) {
    val overLimit = state.draftBytes > state.maxBytes
    val uiStyle = LocalUiStyle.current
    AnimatedVisibility(
        visible = !state.isLoading && (state.hasUnsavedChanges || state.isSaving),
        enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
        exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = if (uiStyle == AppearanceUiStyle.COUI) CouiPageDefaults.horizontalMargin else 12.dp)
                .padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
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
            val discardText = stringResource(R.string.memory_discard)
            val saveText = stringResource(if (state.isSaving) R.string.memory_saving else R.string.memory_save)
            if (uiStyle == AppearanceUiStyle.COUI) {
                CouiButton(
                    text = discardText,
                    enabled = !state.isSaving,
                    onClick = onDiscard,
                    minHeight = 36.dp,
                )
                CouiButton(
                    text = saveText,
                    enabled = state.canSave,
                    onClick = onSave,
                    role = CouiButtonRole.Primary,
                    minHeight = 36.dp,
                )
            } else {
                TextButton(
                    text = discardText,
                    enabled = !state.isSaving,
                    onClick = onDiscard,
                    minHeight = 36.dp,
                    insideMargin = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                )
                TextButton(
                    text = saveText,
                    enabled = state.canSave,
                    onClick = onSave,
                    minHeight = 36.dp,
                    insideMargin = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
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

/** 历史快照时间：本地时区，精确到分钟（秒级差异对「选哪一版」没有帮助）。 */
private fun formatTimestamp(millis: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
