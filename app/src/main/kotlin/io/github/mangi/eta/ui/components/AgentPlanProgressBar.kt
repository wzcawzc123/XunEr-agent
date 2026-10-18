package io.github.mangi.eta.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.agent.plan.AgentPlanUi
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 输入框上方的任务清单进度条。
 *
 * 设计原则：
 * - **只在有未完成步骤时出现**。没有 todo 的会话（绝大多数）完全不受影响、不占高度 ——
 *   「不打扰」优先于「可发现性」。
 * - 一行文字 + 一条细进度线，不抢注意力：扫一眼就知道「在第几步、在做什么」；
 *   完整清单在聊天记录里（todo_write 回执）。
 * - 全部完成后自动消失，不留下已完成列表的视觉噪音。
 *
 * 进度线用 Box + background 手绘，不依赖 Material3 的 LinearProgressIndicator ——
 * 本项目的 UI 栈是 Miuix，混入 Material 组件会在主题/圆角上与周围不一致。
 */
@Composable
internal fun AgentPlanProgressBar(
    plan: AgentPlanUi,
    modifier: Modifier = Modifier,
) {
    if (!plan.isActive || plan.total <= 0) return

    val fraction = (plan.done.toFloat() / plan.total.toFloat()).coerceIn(0f, 1f)
    val headline = plan.running ?: "下一步待开始"

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "${plan.done}/${plan.total}",
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.primary,
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MiuixTheme.colorScheme.surfaceVariant),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MiuixTheme.colorScheme.primary),
            )
        }
        Text(
            text = headline,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1.4f, fill = false),
        )
    }
}
