package io.github.mangi.eta.ui.screens.usage

import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
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
import io.github.mangi.eta.ui.components.EtaCard
import io.github.mangi.eta.ui.components.EtaPreferenceGroupItem
import io.github.mangi.eta.ui.components.EtaPreferenceRow
import top.yukonga.miuix.kmp.basic.PullToRefresh
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
 * 4. （v3.13.4）按模型分组：总账之外，钱花在哪个模型上一目了然
 *
 * 刻意只读、无副作用、无网络：这一页不产生任何费用。
 * 数据在进入页面时读一次，支持下拉刷新（U4，v3.13.4）。
 *
 * 布局与交互统一到项目标准（v3.13.4）：EtaCard（24dp 圆角）+ EtaPreferenceGroupItem/
 * EtaPreferenceRow（与设置页/记忆页同款 squircle 分段与分割线），消除与其他页面的割裂感。
 */
@Composable
internal fun AgentUsageScreen() {
    val context = LocalContext.current
    var all by remember { mutableStateOf<AgentCostSummary?>(null) }
    var refreshing by remember { mutableStateOf(false) }

    suspend fun load() {
        // **必须切到 IO 线程**：LaunchedEffect 默认跑在主线程，
        // 而 Room 的同步查询在主线程会抛 IllegalStateException。
        all = withContext(Dispatchers.IO) {
            runCatching {
                AgentCostRepository(EtaDatabase.get(context).conversationDao()).all()
            }.getOrNull()
        }
    }

    // U4（v3.13.4）：onRefresh 是非 suspend 回调（miuix Function0），
    // 不能直接调 suspend load()；改为置 refreshing 标记，由 LaunchedEffect 触发加载。
    LaunchedEffect(refreshing) {
        if (refreshing) {
            load()
            refreshing = false
        }
    }

    LaunchedEffect(Unit) { load() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            // 不透明底色：否则会透出下层被覆盖的设置页（真机反馈「页面成了这样」）。
            .background(MiuixTheme.colorScheme.surface),
    ) {
        PullToRefresh(
            isRefreshing = refreshing,
            onRefresh = { refreshing = true },
        ) {
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
                        if (summary.byModel.isNotEmpty()) ByModelCard(summary)
                    }
                }
            }
        }
    }
}

@Composable
private fun LoadingCard() {
    EtaCard(modifier = Modifier.fillMaxWidth()) {
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
    EtaCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "还没有用量记录。发出第一条消息后，这里会出现统计。",
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(20.dp),
        )
    }
}

/** 卡片外壳：与记忆页/设置页同款 EtaCard + PreferenceGroup 分段，保证全局视觉一致。 */
@Composable
private fun EtaMetricCard(
    title: String,
    subtitle: String? = null,
    content: @Composable () -> Unit,
) {
    EtaCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                text = title,
                style = MiuixTheme.textStyles.body1,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurface,
            )
            subtitle?.let {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = it,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

/** 指标行：EtaPreferenceRow（标题 + 行尾数值 + 分割线），与设置页同款。 */
@Composable
private fun MetricRow(label: String, value: String, isLast: Boolean = false) {
    EtaPreferenceGroupItem(isFirst = false, isLast = isLast) {
        EtaPreferenceRow(
            title = label,
            endActions = {
                Text(
                    text = value,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurface,
                )
            },
        )
    }
}

@Composable
private fun TotalCard(summary: AgentCostSummary) {
    EtaMetricCard(title = "总量", subtitle = "所有会话累计") {
        MetricRow("请求轮次", formatCount(summary.turns.toLong()))
        MetricRow("输入 token", formatTokens(summary.inputTokens))
        MetricRow("输出 token", formatTokens(summary.outputTokens), isLast = summary.reasoningTokens <= 0)
        if (summary.reasoningTokens > 0) {
            MetricRow("推理 token", formatTokens(summary.reasoningTokens), isLast = true)
        }
    }
}

@Composable
private fun CacheCard(summary: AgentCostSummary) {
    val rate = summary.cacheHitRate
    EtaMetricCard(title = "缓存命中", subtitle = "命中越高越省：未命中的输入按全价计费。") {
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
    }
}

@Composable
private fun BillableCard(summary: AgentCostSummary) {
    EtaMetricCard(title = "实际计费输入", subtitle = "＝ 输入 − 缓存命中。这一项才是真正按全价付钱的部分。") {
        MetricRow("计费输入", formatTokens(summary.billableInputTokens), isLast = true)
    }
}

/** U3（v3.13.4）：按模型分组——总账之外，每个模型一行，钱花在哪一目了然。 */
@Composable
private fun ByModelCard(summary: AgentCostSummary) {
    EtaMetricCard(title = "按模型分组", subtitle = "每个模型独立累计；按输入 token 降序。") {
        val models = summary.byModel
        models.forEachIndexed { index, model ->
            val value = "${formatCount(model.turns.toLong())} 轮 · ${formatTokens(model.inputTokens)} 输入"
            MetricRow(
                label = model.displayName,
                value = value,
                isLast = index == models.lastIndex,
            )
        }
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
