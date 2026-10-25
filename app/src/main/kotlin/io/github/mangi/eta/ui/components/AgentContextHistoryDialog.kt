package io.github.mangi.eta.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.model.AgentContextHistory
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.layout.DialogDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 压缩历史面板（W3）：时间线 + 摘要预览/编辑 + 被折叠计数。
 *
 * 数据来自 [AgentContextHistory.records]（会话 history 投影）；编辑通过 [onEditSummary]
 * 回写对应 history 下标的消息内容，与「手动压缩」同一会话状态通道。
 */
@Composable
internal fun AgentContextHistoryDialog(
    records: List<AgentContextHistory.Record>,
    onDismiss: () -> Unit,
    onEditSummary: (Int, String) -> Unit,
) {
    WindowDialog(
        show = true,
        cornerRadius = DialogDefaults.CornerRadius,
        title = stringResource(R.string.context_history_title),
        summary = stringResource(R.string.context_history_count, records.size),
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (records.isEmpty()) {
                Text(
                    text = stringResource(R.string.context_history_empty),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            records.forEach { record ->
                CompactionRecordCard(record, onEditSummary)
            }
        }
        MiuixDialogActions(
            confirmText = stringResource(R.string.action_done),
            onCancel = onDismiss,
            onConfirm = onDismiss,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

@Composable
private fun CompactionRecordCard(
    record: AgentContextHistory.Record,
    onEditSummary: (Int, String) -> Unit,
) {
    var expanded by remember(record.historyIndex) { mutableStateOf(false) }
    var editing by remember(record.historyIndex) { mutableStateOf(false) }
    var draft by remember(record.historyIndex) { mutableStateOf(record.summaryText) }
    EtaCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            val meta = buildList {
                add(stringResource(R.string.context_history_ordinal, record.ordinal))
                if (record.atMillis != null) add(formatTime(record.atMillis))
                record.tokensBefore?.let {
                    add(stringResource(R.string.context_history_tokens_before, formatTokens(it)))
                }
                if (record.coveredUserTurns > 0) {
                    add(stringResource(R.string.context_history_covered, record.coveredUserTurns))
                }
                record.droppedMessages?.let {
                    add(stringResource(R.string.context_history_dropped, it))
                }
            }.joinToString(" · ")
            Text(
                text = meta,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            if (editing) {
                TextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .heightIn(max = 220.dp),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    DialogTextAction(
                        text = stringResource(R.string.action_done),
                        onClick = {
                            onEditSummary(record.historyIndex, draft)
                            editing = false
                        },
                        modifier = Modifier.weight(1f),
                    )
                    DialogTextAction(
                        text = stringResource(R.string.action_cancel),
                        onClick = {
                            draft = record.summaryText
                            editing = false
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            } else {
                Text(
                    text = if (expanded) record.summaryText else record.summaryText.take(120),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .clickable { expanded = !expanded },
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    DialogTextAction(
                        text = stringResource(
                            if (expanded) R.string.context_history_collapse
                            else R.string.context_history_expand,
                        ),
                        onClick = { expanded = !expanded },
                        modifier = Modifier.weight(1f),
                    )
                    DialogTextAction(
                        text = stringResource(R.string.context_history_edit),
                        onClick = { editing = true },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun DialogTextAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.body2,
        fontWeight = FontWeight.Medium,
        color = MiuixTheme.colorScheme.primary,
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
    )
}

private fun formatTime(millis: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(millis))

private fun formatTokens(value: Int): String = when {
    value >= 100_000 -> String.format(Locale.getDefault(), "%.1f万", value / 10_000.0)
    value >= 10_000 -> String.format(Locale.getDefault(), "%.0fk", value / 1_000.0)
    else -> value.toString()
}
