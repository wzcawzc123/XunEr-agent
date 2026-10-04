package io.github.mangi.eta.agent.media

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.firebase.components.ComponentRegistrar
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.util.concurrent.TimeUnit

internal data class RecognizedText(val text: String, val bounds: Rect)

/**
 * `locate_on_screen` 的 OCR 通道（M2.1 第 2 级，2026-10-04 用户放行体积代价）。
 *
 * 背景：树匹配对"目标不在无障碍树里"的场景（KSU Compose 列表，conv-e7b0a537 实证）
 * 无能为力——OCR 对本机截图像素直接求文字 bbox。接口化以便测试注入假识别器。
 */
internal interface ScreenTextRecognizer {
    /** 识别截图像素里的文字；不可用/超时/无文字返回 null，调用方保持 LOCATE_MISS 语义。 */
    fun recognize(bitmap: Bitmap): List<RecognizedText>?

    /** 最近一次失败原因（含取证），供 locate 的错误载荷与 logcat 定罪，成功时清空。 */
    val lastFailure: String? get() = null

    fun close() {}
}

/**
 * ML Kit 中文离线识别（bundled 模型，不依赖 GMS 下载）。
 *
 * v3.6.0/v3.6.1 真机三连失败链（2026-10-04 定罪）：
 * `TextRecognition.getClient → MlKitContext.get(zzo) == null → NPE` —— MlKitInitProvider
 * 已跑、ComponentRuntime 已建，但 TextRegistrar 的组件没有进入注册表（启动期瞬态，
 * 静态面 manifest/meta-data/类保留全部无误）。因此失败时执行 [forensicsAndHeal]：
 * 手动复现 ComponentDiscovery → 反射重灌 MlKitContext → 重试一次；诊断进载荷。
 */
internal class MlKitScreenTextRecognizer(private val context: Context) : ScreenTextRecognizer {

    @Volatile
    private var cachedClient: com.google.mlkit.vision.text.TextRecognizer? = null

    private val clientLock = Any()

    private fun obtainClient(): com.google.mlkit.vision.text.TextRecognizer =
        cachedClient ?: synchronized(clientLock) {
            cachedClient ?: TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
                .also { cachedClient = it }
        }

    private fun clearClient() {
        synchronized(clientLock) { cachedClient = null }
    }

    override val lastFailure: String? get() = failure

    @Volatile
    private var failure: String? = null

    override fun recognize(bitmap: Bitmap): List<RecognizedText>? {
        val recognizer = when (val acquired = clientWithHeal()) {
            is ClientResult.Ready -> { failure = null; acquired.client }
            is ClientResult.Failed -> return null
        }
        return try {
            val text = Tasks.await(
                recognizer.process(InputImage.fromBitmap(bitmap, 0)),
                RECOGNIZE_TIMEOUT_SECONDS,
                TimeUnit.SECONDS,
            )
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
        } catch (t: Throwable) {
            // OCR 是降级通道，失败不得阻断工具主路径——但必须留痕。
            failure = "${t.javaClass.name}: ${t.message}"
            Log.e("EtaOcr", "recognize failed", t)
            null
        }
    }

    private sealed interface ClientResult {
        data class Ready(val client: com.google.mlkit.vision.text.TextRecognizer) : ClientResult
        data class Failed(val reason: String) : ClientResult
    }

    private fun clientWithHeal(): ClientResult = try {
        ClientResult.Ready(obtainClient())
    } catch (first: Throwable) {
        Log.e("EtaOcr", "getClient failed, forensics+heal", first)
        val heal = forensicsAndHeal()
        clearClient()
        try {
            ClientResult.Ready(obtainClient())
        } catch (second: Throwable) {
            val reason = "getClient:${first.javaClass.simpleName}:${first.message}" +
                " | heal=$heal" +
                " | retry:${second.javaClass.simpleName}:${second.message}"
            failure = reason
            Log.e("EtaOcr", reason, second)
            ClientResult.Failed(reason)
        }
    }

    /**
     * 进程内取证 + 自愈（v3.6.2）：读 MlKitContext 状态 → 手动复现 ComponentDiscovery
     * （manifest meta-data → registrar 实例化）→ 探测 zzo 在不在册 → 不在册则反射
     * 清静态实例并用 `initializeIfNeeded(Context, List)` 重建注册表，上层再重试。
     * 逐段捕获，返回一句话摘要直接进 LOCATE 错误载荷与 logcat（tag=EtaOcr）。
     */
    private fun forensicsAndHeal(): String {
        val steps = StringBuilder()
        try {
            val ctxClass = Class.forName("com.google.mlkit.common.sdkinternal.MlKitContext")
            val holderField = ctxClass.declaredFields
                .first { it.type == ctxClass && java.lang.reflect.Modifier.isStatic(it.modifiers) }
                .apply { isAccessible = true }
            val runtimeField = ctxClass.declaredFields
                .first { it.type.name == "com.google.firebase.components.ComponentRuntime" }
                .apply { isAccessible = true }
            val existing = holderField.get(null)
            steps.append("ctx=").append(existing != null)
            val runtime = existing?.let { runCatching { runtimeField.get(it) }.getOrNull() }
            steps.append(",rt=").append(runtime != null)

            val svcClass = Class.forName("com.google.mlkit.common.internal.MlKitComponentDiscoveryService")
            val serviceInfo = context.packageManager.getServiceInfo(
                ComponentName(context, svcClass),
                PackageManager.GET_META_DATA,
            )
            val meta = serviceInfo.metaData
            val names = meta?.keySet()
                ?.filter {
                    it.startsWith("com.google.firebase.components:") &&
                        meta[it] == "com.google.firebase.components.ComponentRegistrar"
                }
                ?.map { it.substringAfter(':') }
                ?: emptyList()
            steps.append(",registrars=").append(names.size)

            val registrars = mutableListOf<ComponentRegistrar>()
            for (name in names) {
                val label = name.substringAfterLast('.')
                try {
                    @Suppress("UNCHECKED_CAST")
                    val registrar = Class.forName(name).getDeclaredConstructor().newInstance() as ComponentRegistrar
                    val componentCount = runCatching { registrar.components.size }.getOrNull() ?: -1
                    steps.append(" |").append(label).append('=').append(componentCount)
                    registrars.add(registrar)
                } catch (t: Throwable) {
                    steps.append(" |").append(label).append(":FAIL ").append(t.javaClass.simpleName)
                }
            }

            val zzoClass = Class.forName("com.google.mlkit.vision.text.internal.zzo")
            fun probeZzo(): Any? = runtime?.let { rt ->
                rt.javaClass.getMethod("get", Class::class.java).invoke(rt, zzoClass)
            }
            val zzoPresent = runCatching { probeZzo() != null }
                .getOrElse { steps.append(",probeErr=").append(it.javaClass.simpleName); false }
            steps.append(",getZzo=").append(zzoPresent)

            if (!zzoPresent && registrars.isNotEmpty()) {
                holderField.set(null, null)
                val init = ctxClass.getMethod(
                    "initializeIfNeeded",
                    Context::class.java,
                    List::class.java,
                )
                init.invoke(null, context, registrars)
                val after = runCatching { probeZzo() != null }.getOrNull()
                steps.append(",heal=ok,reget=").append(after)
            } else if (!zzoPresent) {
                steps.append(",heal=skip(noRegistrars)")
            }
        } catch (t: Throwable) {
            steps.append(",forensicsErr=").append(t.javaClass.simpleName).append(':').append(t.message)
        }
        val summary = steps.toString()
        Log.w("EtaOcr", "forensics: $summary")
        return summary
    }

    override fun close() {
        synchronized(clientLock) {
            cachedClient?.let { runCatching { it.close() } }
            cachedClient = null
        }
    }

    private companion object {
        // 首次识别要加载 11MB pipeline so + 模型，5s 冷启动超时曾致真机三连失败。
        const val RECOGNIZE_TIMEOUT_SECONDS = 15L
    }
}
