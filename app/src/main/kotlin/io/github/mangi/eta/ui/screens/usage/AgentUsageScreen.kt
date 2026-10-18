package io.github.mangi.eta.ui.screens.usage

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.agent.cost.AgentCostRepository
import io.github.mangi.eta.agent.cost.AgentCostSummary
import io.github.mangi.eta.data.db.EtaDatabase
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 用量统计页。
 *
 * 设计取自主流观测工具的共性（见 llm-usage-dashboard / MLflow / Braintrust 的实践）：
 * 先给**三件事**，不堆图表 ——
 * 1. 总量（多少轮、多少 token）
 * 2. **缓存命中率**（省钱的关键指标，也是「有没有破坏 prompt 缓存」的直接证据）
 * 3. **可计费输入**（真正按全价付钱的部分）
 *
 * 刻意只读、无副作用、无网络：这一页不产生任何费用。
 * 数据在进入页面时读一次（LaunchedEffect），之后不轮询 —— 用量不会在一个页面停留期间大幅变化。
 */
@Composable
internal fun AgentUsageScreen() {
    val context = LocalContext.current
    var all by remember { mutableStateOf<AgentCostSummary?>(null) }

    LaunchedEffect(Unit) {
        all = runCatching {
            AgentCostRepository(EtaDatabase.get(context).conversationDao()).all()
        }.getOrNull()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "用量统计",
            style = MiuixTheme.textStyles.title2,
            color = MiuixTheme.colorScheme.onSurface,
        )
        Text(
            text = "统计所有会话里已发出的请求；本地提示与占位不计入。",
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )

        val summary = all
        when {
            summary == null -> LoadingCard()
            summary.isEmpty -> EmptyCard()
            else -> {
                TotalCard(summary)
                CacheCard(summary)
                BillableCard(summary)
            }
        }
    }
}

@Composable
private fun LoadingCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "正在统计…",
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(20.dp),
        )
    }
}

@Composable
private fun EmptyCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "还没有用量记录。发出第一条消息后，这里会出现统计。",
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(20.dp),
        )
    }
}

@Composable
private fun TotalCard(summary: AgentCostSummary) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionTitle("总量")
            MetricRow("请求轮次", formatCount(summary.turns.toLong()))
            MetricRow("输入 token", formatTokens(summary.inputTokens))
            MetricRow("输出 token", formatTokens(summary.outputTokens))
            if (summary.reasoningTokens > 0) {
                MetricRow("推理 token", formatTokens(summary.reasoningTokens))
            }
        }
    }
}

@Composable
private fun CacheCard(summary: AgentCostSummary) {
    val rate = summary.cacheHitRate
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionTitle("缓存命中")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${(rate * 100).toInt()}%",
                    style = MiuixTheme.textStyles.title2,
                    color = MiuixTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(12.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(MiuixTheme.colorScheme.surfaceVariant),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(rate)
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(MiuixTheme.colorScheme.primary),
                    )
                }
            }
            Text(
                text = "命中越高越省：未命中的输入按全价计费。",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

@Composable
private fun BillableCard(summary: AgentCostSummary) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionTitle("实际计费输入")
            Text(
                text = formatTokens(summary.billableInputTokens),
                style = MiuixTheme.textStyles.title2,
                color = MiuixTheme.colorScheme.onSurface,
            )
            Text(
                text = "＝ 输入 − 缓存命中。这一项才是真正按全价付钱的部分。",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.headline2,
        color = MiuixTheme.colorScheme.onSurface,
    )
}

@Composable
private fun MetricRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Text(
            text = value,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurface,
        )
    }
}

/** 轮次按千分位，读起来比裸数字快。 */
private fun formatCount(value: Long): String {
    val digits = value.toString()
    val sb = StringBuilder()
    digits.forEachIndexed { index, ch ->
        if (index > 0 && (digits.length - index) % 3 == 0) sb.append(',')
        sb.append(ch)
    }
    return sb.toString()
}

/**
 * token 用「亿 / 万」而不是原始数字：真机已是 17 亿级别，
 * 长串数字既难读，也会把行挤到换行。
 */
private fun formatTokens(value: Long): String = when {
    value <= 0 -> "0"
    value >= 100_000_000 -> "%.2f 亿".format(value / 100_000_000.0)
    value >= 10_000 -> "%.1f 万".format(value / 10_000.0)
    else -> formatCount(value)
}
