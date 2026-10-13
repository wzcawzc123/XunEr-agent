package io.github.mangi.eta.ui.screens.terminal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.ui.components.EtaTextButton
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 终端区域的居中占位：空态、环境未就绪与会话断开共用同一种层级与留白。 */
@Composable
internal fun TerminalPlaceholder(
    icon: ImageVector,
    message: String,
    modifier: Modifier = Modifier,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
    secondaryActionText: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
) {
    val colors = MiuixTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .squircleBackground(colors.surfaceContainer, 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(26.dp),
                tint = colors.onSurfaceVariantSummary,
            )
        }
        Text(
            text = message,
            style = MiuixTheme.textStyles.footnote1,
            color = colors.onSurfaceVariantSummary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 14.dp),
        )
        if (actionText != null && onAction != null) {
            EtaTextButton(
                text = actionText,
                onClick = onAction,
                colors = ButtonDefaults.textButtonColorsPrimary(),
                modifier = Modifier.padding(top = 18.dp),
            )
        }
        if (secondaryActionText != null && onSecondaryAction != null) {
            EtaTextButton(
                text = secondaryActionText,
                onClick = onSecondaryAction,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}