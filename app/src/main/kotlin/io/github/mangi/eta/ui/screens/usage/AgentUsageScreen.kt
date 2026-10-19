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
import androidx.compose.ui.text.font.FontWeight
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
 * v3.13.8 重排：原来三张卡各带一份标题+副标题，六个数字撑一整屏、信息密度极低。
 * 现在按「结论 → 明细」两层组织：
 * 1. **Hero 卡**：真正按全价付钱的「计费输入」放大展示，缓存命中率与"省了多少"作为
 *    它的解释 —— 用户扫一眼就能回答"这次为什么没超预算"；
 * 2. **明细卡**：全部原始数字收进一张卡的分行里，去掉重复的卡片外壳开销；
 * 3. **按模型**：保留（v3.13.4），钱花在哪个模型上一目了然。
 *
 * 刻意只读、无副作用、无网络：这一页不产生任何费用。
 * 数据在进入页面时读一次，支持下拉刷新（U4，v3.13.4）。
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
                // 副标题只留一句；轮次放 hero/明细里，不再单独占一行解释。
                Text(
                    text = "统计所有会话已发出的请求；本地提示与占位不计入。",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )

                val summary = all
                when {
                    summary == null -> LoadingCard()
                    summary.isEmpty -> EmptyCard()
                    else -> {
                        HeroCard(summary)
                        DetailCard(summary)
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

/**
 * 结论卡：计费输入大字 + 缓存命中率条 + 一句"为什么"。
 *
 * 之所以把三张卡压成一张：缓存命中率、计费输入、输入总量本来就是同一件事的三个面
 * （计费输入 = 输入 × (1 − 命中率)），拆三张卡反而让用户自己做算术。
 */
@Composable
private fun HeroCard(summary: AgentCostSummary) {
    val rate = summary.cacheHitRate
    val ratePercent = (rate * 100).toInt()
    EtaCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(
                text = "实际计费输入",
                style = MiuixTheme.textStyles.body1,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = formatTokens(summary.billableInputTokens),
                style = MiuixTheme.textStyles.headline1,
                color = MiuixTheme.colorScheme.primary,
            )

            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "缓存命中率",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "$ratePercent%",
                    style = MiuixTheme.textStyles.body2,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(6.dp))
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
            Spacer(Modifier.height(8.dp))
            Text(
                // 用命中率直接给结论，不再让用户自己拿两个数相减。
                text = "输入的 $ratePercent% 走了缓存价；只有未命中的部分按全价计费。",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

/** 明细卡：全部原始数字收进一张卡，行间分割线与设置页同款。 */
@Composable
private fun DetailCard(summary: AgentCostSummary) {
    EtaCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                text = "明细",
                style = MiuixTheme.textStyles.body1,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(10.dp))
            MetricRow("输入 token", formatTokens(summary.inputTokens))
            MetricRow("其中缓存命中", formatTokens(summary.cachedTokens))
            MetricRow("输出 token", formatTokens(summary.outputTokens))
            if (summary.reasoningTokens > 0) {
                MetricRow("推理 token", formatTokens(summary.reasoningTokens))
            }
            MetricRow("请求轮次", formatCount(summary.turns.toLong()), isLast = true)
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

/** U3（v3.13.4）：按模型分组——总账之外，每个模型一行，钱花在哪一目了然。 */
@Composable
private fun ByModelCard(summary: AgentCostSummary) {
    EtaCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                text = "按模型",
                style = MiuixTheme.textStyles.body1,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(10.dp))
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
