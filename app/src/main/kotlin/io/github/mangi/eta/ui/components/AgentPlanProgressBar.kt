package io.github.mangi.eta.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.agent.plan.AgentPlanStatus
import io.github.mangi.eta.agent.plan.AgentPlanUi
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 输入框上方的任务清单进度条（可收起 / 展开）。
 *
 * 设计：
 * - **收起态**（默认）：一行——`1/3` + 进度线 + 当前步骤，尽量不占高度。
 * - **展开态**：整条清单向上展开（消息区被压缩，输入框位置不变），便于"临时看一眼还剩什么"。
 * - 默认收起，因为绝大多数轮次用户只想知道"到第几步了"，不需要看全文。
 * - 全部完成时整个组件不渲染。
 *
 * 布局细节：
 * - 左右外边距与输入框一致（16dp），底部不留额外间距，紧贴输入框 —— 视觉上属于输入区。
 * - 展开后高度受限并可滚动：清单可能十几项，不能把消息区挤没。
 */
@Composable
internal fun AgentPlanProgressBar(
    plan: AgentPlanUi,
    modifier: Modifier = Modifier,
) {
    if (plan.total <= 0) return
    if (!plan.isActive) return

    var expanded by remember { mutableStateOf(false) }
    val fraction = (plan.done.toFloat() / plan.total.toFloat()).coerceIn(0f, 1f)
    val headline = if (plan.isTerminalWithCancelled) {
        // 全部结束但有取消项：不能显示成"下一步待开始"，更不能让组件直接消失。
        "已结束 · ${plan.cancelled} 项取消"
    } else {
        plan.running ?: "下一步待开始"
    }
    // 箭头旋转动画：收起 0°，展开 180°
    val arrowAngle by animateFloatAsState(if (expanded) 180f else 0f, label = "planArrow")

    Column(
        modifier = modifier
            .fillMaxWidth()
            // 左右边距由调用方（输入区 Column）统一控制：组件自己再缩进会与输入框错位。
            .clip(RoundedCornerShape(14.dp))
            .background(MiuixTheme.colorScheme.surfaceVariant),
    ) {
        // ---- 收起态：一行 ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 14.dp, vertical = 10.dp),
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
            // 用文字箭头而不是图标库：本项目的图标集里没有 chevron，自绘更稳。
            Text(
                text = "▾",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.rotate(arrowAngle),
            )
        }

        // ---- 展开态：完整清单（向上撑开，输入框不动）----
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 260.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                plan.items.forEach { item ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val (mark, color) = when (item.status) {
                            AgentPlanStatus.COMPLETED -> "✓" to MiuixTheme.colorScheme.onSurfaceVariantSummary
                            AgentPlanStatus.IN_PROGRESS -> "▸" to MiuixTheme.colorScheme.primary
                            AgentPlanStatus.CANCELLED -> "✕" to MiuixTheme.colorScheme.onSurfaceVariantSummary
                            AgentPlanStatus.PENDING -> "○" to MiuixTheme.colorScheme.onSurfaceVariantSummary
                        }
                        Text(text = mark, style = MiuixTheme.textStyles.footnote1, color = color)
                        Text(
                            // 取消项必须有独立视觉：✕ + 后缀，不能和「待开始」一样是 ○。
                            text = if (item.status == AgentPlanStatus.CANCELLED) {
                                "${item.subject}（已取消）"
                            } else {
                                item.subject
                            },
                            style = MiuixTheme.textStyles.footnote1,
                            color = if (item.status == AgentPlanStatus.IN_PROGRESS) {
                                MiuixTheme.colorScheme.onSurface
                            } else {
                                MiuixTheme.colorScheme.onSurfaceVariantSummary
                            },
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
