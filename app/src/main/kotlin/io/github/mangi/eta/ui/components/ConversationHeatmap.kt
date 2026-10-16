package io.github.mangi.eta.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 会话活跃度热力图（spec memory-upgrade §3.4）：GitHub 风格 16 周网格，
 * 按天统计会话数（非消息数）。纯展示组件，数据由调用方聚合传入。
 */
@Composable
internal fun ConversationHeatmap(
    days: List<ConversationActivityDay>,
    today: LocalDate = LocalDate.now(),
    modifier: Modifier = Modifier,
) {
    val accent = MiuixTheme.colorScheme.primary
    val emptyColor = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.15f)
    val buckets = days.associate { it.date to it.conversationCount }
    val weeks = buildWeeks(today)
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "会话活跃度",
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurface,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(88.dp),
        ) {
            val cell = size.width / (weeks.size * 1.6f)
            val gap = cell * 0.35f
            weeks.forEachIndexed { weekIndex, week ->
                week.days.forEachIndexed { dayIndex, day ->
                    if (day != null) {
                        val count = buckets[day] ?: 0
                        drawRect(
                            color = heatColor(count, accent, emptyColor),
                            topLeft = Offset(
                                weekIndex * (cell + gap),
                                dayIndex * (cell + gap),
                            ),
                            size = Size(cell, cell),
                        )
                    }
                }
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 4.dp),
        ) {
            Text(
                text = "少",
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            heatLevels().forEach { count ->
                Spacer(modifier = Modifier.width(4.dp))
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(heatColor(count, accent, emptyColor)),
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "多",
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(modifier = Modifier.weight(1f))
            val total = days.sumOf { it.conversationCount.toLong() }
            val streak = currentStreak(buckets, today)
            Text(
                text = "近 16 周 $total 次对话 · 连续 $streak 天",
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                textAlign = TextAlign.End,
            )
        }
    }
}

/** 某一天的会话活跃度数据点（调用方聚合）。 */
internal data class ConversationActivityDay(
    val date: LocalDate,
    val conversationCount: Int,
)

/** 16 周网格：列 = 周（周一开头），行 = 周一..周日，日 = null 表示该周对应位置无日期。 */
private fun buildWeeks(today: LocalDate): List<WeekDays> {
    val startMonday = today.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
    val gridStart = startMonday.minusWeeks(15)
    val weeks = mutableListOf<WeekDays>()
    var weekStart = gridStart
    repeat(16) {
        val days = (0..6).map { offset ->
            weekStart.plusDays(offset.toLong()).takeIf { it <= today && it >= gridStart }
        }
        weeks += WeekDays(days)
        weekStart = weekStart.plusWeeks(1)
    }
    return weeks
}

private data class WeekDays(val days: List<LocalDate?>)

/** 强度分桶：0、1、2-3、4-6、>6（照 OmniBot ConversationHeatmap）。 */
private fun heatColor(count: Int, accent: Color, emptyColor: Color): Color = when (count) {
    0 -> emptyColor
    1 -> accent.copy(alpha = 0.35f)
    in 2..3 -> accent.copy(alpha = 0.55f)
    in 4..6 -> accent.copy(alpha = 0.75f)
    else -> accent.copy(alpha = 1f)
}

private fun heatLevels(): List<Int> = listOf(0, 1, 3, 6, 10)

/** 从今天往前数的连续非零会话天数。 */
private fun currentStreak(buckets: Map<LocalDate, Int>, today: LocalDate): Int {
    var streak = 0
    var day = today
    while ((buckets[day] ?: 0) > 0) {
        streak++
        day = day.minusDays(1)
    }
    return streak
}
