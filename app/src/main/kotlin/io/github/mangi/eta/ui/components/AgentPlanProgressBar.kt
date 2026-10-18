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
 * - 全部完成后自动消失，不留下已完成列表的视觉噪音。
 * - **必须有不透明底色**：早期版本只画一行透明文字，滚动时会和下面的消息文字叠在一起，
 *   读起来像花屏（真机反馈）。
 * - **左右边距与输入框一致**（16dp），视觉上属于输入区而不是消息流。
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

    Box(
        modifier = modifier
            .fillMaxWidth()
            // 外边距与输入框对齐，让它看起来属于输入区
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            // 不透明底色：避免与滚动中的消息文字叠字
            .background(MiuixTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "${plan.done}/${plan.total}",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.primary,
                maxLines = 1,
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MiuixTheme.colorScheme.surface),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MiuixTheme.colorScheme.primary),
                )
            }
            Text(
                text = headline,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1.4f, fill = false),
            )
        }
    }
}
