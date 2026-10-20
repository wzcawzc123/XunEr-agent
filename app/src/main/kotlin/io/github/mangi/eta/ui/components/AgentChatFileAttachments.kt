package io.github.mangi.eta.ui.components

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.model.AgentFileReference
import io.github.mangi.eta.agent.model.AgentFileReferenceKind
import io.github.mangi.eta.agent.plan.AgentPlanPhase
import io.github.mangi.eta.ui.model.PendingFileReferenceUi
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.LocalDismissState
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.layout.DialogDefaults
import top.yukonga.miuix.kmp.window.WindowDialog
import top.yukonga.miuix.kmp.window.WindowListPopup
import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

internal val ChatInputPopupMargin = 8.dp
internal val ChatInputActionSize = 40.dp
internal val ChatInputActionIconSize = 24.dp
internal val maxMediaItems: Int = 9

@Composable
internal fun AgentAttachmentPickerButton(
    popupAnchorTopPx: Int,
    popupMaxHeight: Dp,
    onAttachImage: (String) -> Unit,
    onAttachFiles: (List<String>) -> Unit,
    onAttachFolder: (String) -> Unit,
    onAttachFilePath: (String) -> Unit,
    /** 是否已处于计划模式：决定菜单项显示「计划」还是「退出计划」。 */
    planActive: Boolean = false,
    /**
     * 当前阶段能否由用户主动退出。
     *
     * REVIEW（待批准）为 false：此时放弃计划必须走审批条的「取消计划」，
     * 菜单项改成「计划待批准」并禁用点击，而不是给一个点了没反应的入口。
     */
    planActionable: Boolean = true,
    /** 进入计划模式（只切换阶段，不发消息）。 */
    onEnterPlanMode: () -> Unit = {},
    /** 退出计划模式（纯本地放弃，不发消息）。 */
    onExitPlanMode: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var showPopup by remember { mutableStateOf(false) }
    var showPathDialog by remember { mutableStateOf(false) }
    var pathInput by remember { mutableStateOf("") }
    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxMediaItems),
    ) { uris ->
        uris.forEach { uri ->
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            onAttachImage(uri.toString())
        }
    }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(),
    ) { success ->
        if (success) cameraUri?.let { onAttachImage(it.toString()) }
        cameraUri = null
    }
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) onAttachFiles(uris.map { it.toString() })
    }
    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) onAttachFolder(uri.toString())
    }

    Box(modifier = modifier) {
        IconButton(
            onClick = { showPopup = true },
            minWidth = ChatInputActionSize,
            minHeight = ChatInputActionSize,
        ) {
            Icon(
                imageVector = Icons.Rounded.Add,
                contentDescription = stringResource(R.string.ui_add_attachment_dba9e8),
                modifier = Modifier.size(ChatInputActionIconSize),
                tint = MiuixTheme.colorScheme.onSurface,
            )
        }
        WindowListPopup(
            show = showPopup && popupAnchorTopPx > 0,
            popupPositionProvider = remember(popupAnchorTopPx) {
                InputPopupPositionProvider(popupAnchorTopPx)
            },
            alignment = PopupPositionProvider.Align.TopStart,
            onDismissRequest = { showPopup = false },
            maxHeight = popupMaxHeight,
        ) {
            val dismiss = LocalDismissState.current
            // 「计划」放在附件项之后：它是模式开关而非附件，但入口藏在加号里，
            // 避免在输入栏那一排固定占位（那一排全是同尺寸图标按钮，插一个带文字的
            // pill 会挤占空间且高度不齐 —— 真机反馈）。
            val options = listOf(
                stringResource(R.string.attachment_camera),
                stringResource(R.string.attachment_image),
                stringResource(R.string.attachment_file),
                stringResource(R.string.attachment_folder),
                stringResource(R.string.attachment_enter_path),
                when {
                    !planActionable -> stringResource(R.string.chat_plan_mode_pending)
                    planActive -> stringResource(R.string.chat_plan_mode_exit)
                    else -> stringResource(R.string.chat_plan_mode_enter)
                },
            )
            ListPopupColumn {
                options.forEachIndexed { index, option ->
                    DropdownImpl(
                        text = option,
                        optionSize = options.size,
                        isSelected = false,
                        index = index,
                        onSelectedIndexChange = {
                            dismiss?.invoke()
                            when (index) {
                                0 -> {
                                    val uri = createCameraImageUri(context)
                                    if (uri != null) {
                                        cameraUri = uri
                                        cameraLauncher.launch(uri)
                                    }
                                }
                                1 -> photoPicker.launch(
                                    PickVisualMediaRequest(
                                        ActivityResultContracts.PickVisualMedia.ImageOnly
                                    )
                                )
                                2 -> filePicker.launch(arrayOf("*/*"))
                                3 -> folderPicker.launch(null)
                                4 -> {
                                    pathInput = ""
                                    showPathDialog = true
                                }
                                5 -> if (planActionable) {
                                    if (planActive) onExitPlanMode() else onEnterPlanMode()
                                }
                            }
                        },
                    )
                }
            }
        }
    }

    WindowDialog(
        show = showPathDialog,
        cornerRadius = DialogDefaults.CornerRadius,
        title = stringResource(R.string.ui_input_file_path_36d474),
        summary = stringResource(R.string.ui_supports_files_and_folders_under_internal_storage_or_520786),
        onDismissRequest = { showPathDialog = false },
    ) {
        Column {
            TextField(
                value = pathInput,
                onValueChange = { pathInput = it },
                label = stringResource(R.string.ui_absolute_path_9ac6fc),
                useLabelAsPlaceholder = true,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            MiuixDialogActions(
                confirmText = stringResource(R.string.attachment_add),
                confirmEnabled = pathInput.trim().startsWith('/'),
                onCancel = { showPathDialog = false },
                onConfirm = {
                    val path = pathInput.trim()
                    showPathDialog = false
                    onAttachFilePath(path)
                },
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
}

@Composable
internal fun PendingFileReferenceStrip(
    references: List<PendingFileReferenceUi>,
    onRemoveReference: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        references.forEach { pending ->
            val reference = pending.reference
            Row(
                modifier = Modifier
                    .height(42.dp)
                    .widthIn(max = 250.dp)
                    .squircleSurface(
                        color = MiuixTheme.colorScheme.surfaceContainerHigh,
                        cornerRadius = 14.dp,
                    )
                    .squircleBorder(
                        width = 0.5.dp,
                        color = MiuixTheme.colorScheme.outline.copy(alpha = 0.5f),
                        cornerRadius = 14.dp,
                    )
                    .padding(start = 12.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = if (reference.kind == AgentFileReferenceKind.Directory) {
                        Icons.Rounded.FolderOpen
                    } else {
                        Icons.Rounded.Description
                    },
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MiuixTheme.colorScheme.primary,
                )
                Text(
                    text = reference.displayName +
                        if (reference.kind == AgentFileReferenceKind.Directory) "/" else "",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .clickable { onRemoveReference(pending.id) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.ui_remove_file_reference_04bbfc),
                        modifier = Modifier.size(15.dp),
                        tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                    )
                }
            }
        }
    }
}

/**
 * 计划模式状态条：输入区里一个**可删除**的 chip，形态与 [PendingFileReferenceStrip] 完全同款。
 *
 * 为什么放在输入区而不是输入栏那一排：那一排全是同尺寸图标按钮，插一个带文字的 pill
 * 会挤占空间且高度不齐（v3.13.8 真机反馈）；而输入区早有"附件 chip"这个先例，
 * 复用它的形态用户零学习成本 —— 点 `×` 即退出计划模式，与删除附件同一个语义。
 *
 * @param phase 非 NORMAL 才显示；NORMAL 时调用方不应渲染（这里直接 return 兜底）。
 * @param onRemove 退出计划模式（纯本地放弃：不发消息、不起 run）。
 */
@Composable
internal fun PlanModeStrip(
    phase: AgentPlanPhase,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = when (phase) {
        AgentPlanPhase.PLANNING -> stringResource(R.string.chat_plan_mode_planning)
        AgentPlanPhase.REVIEW -> stringResource(R.string.chat_plan_mode_review)
        AgentPlanPhase.EXECUTING -> stringResource(R.string.chat_plan_mode_executing)
        AgentPlanPhase.NORMAL -> return
    }
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .height(42.dp)
                .squircleSurface(
                    color = MiuixTheme.colorScheme.surfaceContainerHigh,
                    cornerRadius = 14.dp,
                )
                .squircleBorder(
                    width = 0.5.dp,
                    color = MiuixTheme.colorScheme.outline.copy(alpha = 0.5f),
                    cornerRadius = 14.dp,
                )
                .padding(start = 12.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Checklist,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MiuixTheme.colorScheme.primary,
            )
            Text(
                text = label,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.chat_plan_mode_exit),
                    modifier = Modifier.size(15.dp),
                    tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun SentFileReferenceFlow(
    references: List<AgentFileReference>,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        references.forEach { reference ->
            Row(
                modifier = Modifier
                    .height(38.dp)
                    .widthIn(max = 280.dp)
                    .squircleSurface(
                        color = MiuixTheme.colorScheme.surface,
                        cornerRadius = 12.dp,
                    )
                    .squircleBorder(
                        width = 0.5.dp,
                        color = MiuixTheme.colorScheme.outline.copy(alpha = 0.45f),
                        cornerRadius = 12.dp,
                    )
                    .padding(horizontal = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = if (reference.kind == AgentFileReferenceKind.Directory) {
                        Icons.Rounded.FolderOpen
                    } else {
                        Icons.Rounded.Description
                    },
                    contentDescription = null,
                    modifier = Modifier.size(17.dp),
                    tint = MiuixTheme.colorScheme.primary,
                )
                Text(
                    text = reference.displayName +
                        if (reference.kind == AgentFileReferenceKind.Directory) "/" else "",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

internal class InputPopupPositionProvider(
    private val inputContainerTopPx: Int,
    private val windowHorizontalInsetPx: Int? = null,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowBounds: IntRect,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
        popupMargin: IntRect,
        alignment: PopupPositionProvider.Align,
    ): IntOffset {
        val alignToEnd = when (alignment) {
            PopupPositionProvider.Align.End,
            PopupPositionProvider.Align.TopEnd,
            PopupPositionProvider.Align.BottomEnd,
            -> true

            else -> false
        }
        val physicalEnd = if (layoutDirection == LayoutDirection.Ltr) alignToEnd else !alignToEnd
        val requestedX = when {
            windowHorizontalInsetPx != null && physicalEnd ->
                windowBounds.right - popupContentSize.width - popupMargin.right - windowHorizontalInsetPx

            windowHorizontalInsetPx != null ->
                windowBounds.left + popupMargin.left + windowHorizontalInsetPx

            physicalEnd -> anchorBounds.right - popupContentSize.width - popupMargin.right
            else -> anchorBounds.left + popupMargin.left
        }
        val maxX = (windowBounds.right - popupContentSize.width - popupMargin.right)
            .coerceAtLeast(windowBounds.left)
        val requestedY = inputContainerTopPx - popupContentSize.height - popupMargin.bottom
        val maxY = windowBounds.bottom - popupContentSize.height - popupMargin.bottom
        val minY = (windowBounds.top + popupMargin.top).coerceAtMost(maxY)
        return IntOffset(
            x = requestedX.coerceIn(windowBounds.left, maxX),
            y = requestedY.coerceIn(minY, maxY),
        )
    }

    override fun getMargins(): PaddingValues = PaddingValues(vertical = ChatInputPopupMargin)
}

private fun createCameraImageUri(context: Context): Uri? = runCatching {
    val dir = File(context.cacheDir, "camera").apply { if (!exists()) mkdirs() }
    val file = File(dir, "cam_" + System.currentTimeMillis() + ".jpg")
    FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
}.getOrNull()
