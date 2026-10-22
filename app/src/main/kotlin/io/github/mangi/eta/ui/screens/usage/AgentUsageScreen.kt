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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.input.nestedscroll.nestedScroll
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.cost.AgentCostRepository
import io.github.mangi.eta.agent.cost.AgentCostSummary
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.ui.components.EtaCard
import io.github.mangi.eta.ui.components.EtaPreferenceGroupItem
import io.github.mangi.eta.ui.components.EtaPreferenceRow
import io.github.mangi.eta.ui.components.MiuixScaffold
import io.github.mangi.eta.ui.layout.horizontalCutoutPadding
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 用量统计页。
 *
 * v3.13.8 重排内容：原来三张卡各带一份标题+副标题，六个数字撑一整屏、信息密度极低。
 * 现在按「结论 → 明细」两层组织：
 * 1. **Hero 卡**：真正按全价付钱的「计费输入」放大展示，缓存命中率与"省了多少"作为
 *    它的解释 —— 用户扫一眼就能回答"这次为什么没超预算"；
 * 2. **明细卡**：全部原始数字收进一张卡的分行里，去掉重复的卡片外壳开销；
 * 3. **按模型**：保留（v3.13.4），钱花在哪个模型上一目了然。
 *
 * v3.13.9 统一页面骨架：标题改走 [MiuixScaffold] 的 TopAppBar（与其他页同款居中标题、
 * 高度与返回键）。此前这页是唯一自己写左对齐 `Text` 的页面，观感与记忆/角色页割裂
 * （真机反馈）。这条约定由 `AgentUsageScreenLayoutTest` 锁死。
 *
 * 刻意只读、无副作用、无网络：这一页不产生任何费用。
 * 数据在进入页面时读一次，支持下拉刷新（U4，v3.13.4）。
 */
@Composable
internal fun AgentUsageScreen(onBack: () -> Unit) {
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

    MiuixScaffold(
        title = stringResource(R.string.route_usage_stats),
        onBack = onBack,
    ) { paddingValues, scrollBehavior, sidePadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .horizontalCutoutPadding()
                .padding(top = paddingValues.calculateTopPadding())
                .padding(horizontal = sidePadding)
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .navigationBarsPadding(),
        ) {
            PullToRefresh(
                isRefreshing = refreshing,
                onRefresh = { refreshing = true },
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // 副标题只留一句；轮次放 hero/明细里，不再单独占一行解释。
                    Text(
                        text = stringResource(R.string.usage_scope_hint),
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
}

@Composable
private fun LoadingCard() {
    EtaCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.usage_loading),
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
            text = stringResource(R.string.usage_empty),
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
                text = stringResource(R.string.usage_billable_input),
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
                    text = stringResource(R.string.usage_cache_hit_rate),
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
                text = stringResource(R.string.usage_cache_explain, ratePercent),
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
                text = stringResource(R.string.usage_detail),
                style = MiuixTheme.textStyles.body1,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(10.dp))
            MetricRow(stringResource(R.string.usage_input_tokens), formatTokens(summary.inputTokens))
            MetricRow(stringResource(R.string.usage_cached_tokens), formatTokens(summary.cachedTokens))
            MetricRow(stringResource(R.string.usage_output_tokens), formatTokens(summary.outputTokens))
            if (summary.reasoningTokens > 0) {
                MetricRow(stringResource(R.string.usage_reasoning_tokens), formatTokens(summary.reasoningTokens))
            }
            MetricRow(stringResource(R.string.usage_turns), formatCount(summary.turns.toLong()), isLast = true)
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
                text = stringResource(R.string.usage_by_model),
                style = MiuixTheme.textStyles.body1,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(10.dp))
            val models = summary.byModel
            models.forEachIndexed { index, model ->
                // 与 hero 同口径：每行报「计费输入」而不是原始输入——
                // 用户关心的是"这个模型花了多少钱的量"，缓存命中的部分不该再吓一次人。
                val value = stringResource(
                    R.string.usage_turns_billable_format,
                    formatCount(model.turns.toLong()),
                    formatTokens(model.billableInputTokens),
                )
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
@Composable
private fun formatTokens(value: Long): String = when {
    value <= 0 -> "0"
    value >= 100_000_000 -> stringResource(R.string.usage_unit_hundred_million, value / 100_000_000.0)
    value >= 10_000 -> stringResource(R.string.usage_unit_ten_thousand, value / 10_000.0)
    else -> formatCount(value)
}
