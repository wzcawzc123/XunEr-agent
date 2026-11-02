package io.github.mangi.eta.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.model.AgentContextHistory
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 上下文压缩历史：把「模型现在记得什么」摊开给用户看。
 *
 * 压缩是静默发生的——模型忘了某个约束时，用户只看到「它怎么不记得了」。
 * 这里列出每次压缩覆盖到第几轮、以及当时的交接卡正文：卡片里哪一段被填成「无」，
 * 就说明那部分信息确实没被保留下来（而不是被模型无视）。
 */
@Composable
internal fun AgentContextHistoryDialog(
    records: List<AgentContextHistory.Record>,
    onDismiss: () -> Unit,
) {
    EtaWindowDialog(
        show = true,
        title = stringResource(R.string.context_history_title),
        summary = if (records.isEmpty()) {
            stringResource(R.string.context_history_empty)
        } else {
            stringResource(R.string.context_history_count, records.size)
        },
        onDismissRequest = onDismiss,
    ) {
        if (records.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                records.forEachIndexed { index, record ->
                    if (index > 0) Spacer(Modifier.height(8.dp))
                    HistoryRecordCard(record)
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        EtaTextButton(
            text = stringResource(R.string.action_close),
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun HistoryRecordCard(record: AgentContextHistory.Record) {
    val scheme = MiuixTheme.colorScheme
    var expanded by remember { mutableStateOf(false) }
    // 卡片正文通常 1-3 千字，折叠后只留前几行；展开才渲染全文（避免一次测量太多行）。
    val collapsed = record.summaryText.length > COLLAPSED_CHARS
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(scheme.surfaceContainerHigh, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.context_history_ordinal, record.ordinal),
                style = MiuixTheme.textStyles.body2,
                fontWeight = FontWeight.Medium,
                color = scheme.onSurface,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.context_history_coverage, record.coveredUserTurns),
                style = MiuixTheme.textStyles.footnote2,
                color = scheme.onSurfaceVariantSummary,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = record.summaryText,
            style = MiuixTheme.textStyles.footnote2,
            color = scheme.onSurfaceVariantSummary,
            maxLines = if (expanded) Int.MAX_VALUE else COLLAPSED_LINES,
            overflow = TextOverflow.Ellipsis,
        )
        if (collapsed) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(
                    if (expanded) R.string.context_history_collapse else R.string.context_history_expand,
                ),
                style = MiuixTheme.textStyles.footnote2,
                fontWeight = FontWeight.Medium,
                color = scheme.primary,
                modifier = Modifier.clickable { expanded = !expanded },
            )
        }
    }
}

private const val COLLAPSED_CHARS = 160
private const val COLLAPSED_LINES = 4
