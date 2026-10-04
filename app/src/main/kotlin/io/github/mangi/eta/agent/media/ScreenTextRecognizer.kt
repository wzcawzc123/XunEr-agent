package io.github.mangi.eta.agent.media

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.util.concurrent.TimeUnit

internal data class RecognizedText(val text: String, val bounds: Rect)

/**
 * `locate_on_screen` 的 OCR 通道（M2.1 第 2 级，2026-10-04 用户放行体积代价）。
 *
 * 背景：树匹配通道对"目标不在无障碍树里"的场景（KSU Compose 列表不暴露子项，
 * 2026-10-04 conv-e7b0a537 实证）无能为力——OCR 对本机截图像素直接求文字 bbox，
 * 与树是否存在无关。接口化以便测试注入假识别器（ML Kit 模型无法在单测环境跑）。
 */
internal interface ScreenTextRecognizer {
    /** 识别截图像素里的文字；不可用/超时/无文字返回 null，调用方保持 LOCATE_MISS 语义。 */
    fun recognize(bitmap: Bitmap): List<RecognizedText>?

    /** 最近一次失败原因（类名+消息），供 locate 的错误载荷与 logcat 定罪，成功时清空。 */
    val lastFailure: String? get() = null

    fun close() {}
}

/**
 * ML Kit 中文离线识别（bundled 模型，不依赖 GMS）。
 * 同时产出「行」与「元素」两级候选：行覆盖跨元素查询（如"<>打开"），
 * 元素让精确命中（0.98 档）能压过行级包含（0.8 档），排序交给 ScreenLocator。
 */
internal class MlKitScreenTextRecognizer : ScreenTextRecognizer {

    private val recognizerLazy = lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    private val recognizer by recognizerLazy

    override val lastFailure: String?
        get() = failure

    @Volatile
    private var failure: String? = null

    override fun recognize(bitmap: Bitmap): List<RecognizedText>? = try {
        val text = Tasks.await(
            recognizer.process(InputImage.fromBitmap(bitmap, 0)),
            RECOGNIZE_TIMEOUT_SECONDS,
            TimeUnit.SECONDS,
        )
        this.failure = null
        buildList {
            for (block in text.textBlocks) {
                for (line in block.lines) {
                    line.boundingBox?.let { add(RecognizedText(line.text, it)) }
                    for (element in line.elements) {
                        element.boundingBox?.let { add(RecognizedText(element.text, it)) }
                    }
                }
            }
        }.ifEmpty { null }
    } catch (failure: Exception) {
        // OCR 是降级通道，失败不得阻断工具主路径——但必须留痕（此前静默吞异常
        // 导致真机 OCR 三连 null 无法定罪，2026-10-04 v3.6.0 教训）。
        this.failure = "${failure.javaClass.name}: ${failure.message}"
        android.util.Log.e("EtaOcr", "recognize failed", failure)
        null
    }

    override fun close() {
        // isInitialized 判定必须走 Lazy 句柄本身——直接摸 recognizer 会触发初始化。
        if (recognizerLazy.isInitialized()) runCatching { recognizer.close() }
    }

    private companion object {
        // 首次识别要加载 11MB pipeline so + 模型，5s 冷启动超时曾致真机三连失败。
        const val RECOGNIZE_TIMEOUT_SECONDS = 15L
    }
}
