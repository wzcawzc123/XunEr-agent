package io.github.mangi.eta.agent.media

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.firebase.components.ComponentRegistrar
import com.google.mlkit.common.sdkinternal.MlKitContext
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
     * 进程内取证 + 自愈（v3.6.3，rename-agnostic）。
     *
     * v3.6.2 教训：靠类名字符串反射在 release 下必挂（R8 改名且逐构建变化：
     * ComponentRuntime→fv0、MlKitContext→qz2，NoSuchElementException 吞掉了取证）。
     * v3.6.3 全部改用**编译期类/方法字面量与签名匹配**（R8 同步改名，天然抗混淆）；
     * 字符串仅用于 manifest 指定的组件（consumer keep 与 manifest 组件保名）。
     * 逐步打点，任何一步失败都带标签进 LOCATE 载荷。
     */
    private fun forensicsAndHeal(): String {
        val steps = StringBuilder()
        val ctxClass = MlKitContext::class.java

        val holderField = runCatching {
            ctxClass.declaredFields.first {
                it.type == ctxClass && java.lang.reflect.Modifier.isStatic(it.modifiers)
            }
        }.getOrElse {
            steps.append(",holder=ERR:").append(it.javaClass.simpleName)
            null
        }
        val existing = holderField?.let { field ->
            runCatching { field.isAccessible = true; field.get(null) }.getOrNull()
        }
        steps.append(",ctx=").append(existing != null)

        val runtimeField = existing?.let { inst ->
            ctxClass.declaredFields.firstOrNull { f ->
                !java.lang.reflect.Modifier.isStatic(f.modifiers) && !f.type.isPrimitive &&
                    runCatching { f.isAccessible = true; f.get(inst) != null }.getOrDefault(false)
            }
        }
        steps.append(",rt=").append(runtimeField != null)

        // manifest 发现：service 组件名与 meta-data 值为字符串常量，不受混淆影响。
        val names = runCatching {
            val serviceInfo = context.packageManager.getServiceInfo(
                ComponentName(context.packageName, DISCOVERY_SERVICE),
                PackageManager.GET_META_DATA,
            )
            val meta = serviceInfo.metaData
            meta?.keySet()
                ?.filter {
                    it.startsWith(REGISTRAR_PREFIX) &&
                        meta[it] == "com.google.firebase.components.ComponentRegistrar"
                }
                ?.map { it.substring(REGISTRAR_PREFIX.length) }
                ?: emptyList<String>()
        }.getOrElse {
            steps.append(",meta=ERR:").append(it.javaClass.simpleName)
            emptyList()
        }
        steps.append(",registrars=").append(names.size)

        val registrars = mutableListOf<ComponentRegistrar>()
        for (name in names) {
            val label = name.substringAfterLast('.')
            runCatching {
                val registrar = Class.forName(name).getDeclaredConstructor().newInstance() as ComponentRegistrar
                steps.append(" |").append(label).append('=').append(registrar.components.size)
                registrars.add(registrar)
            }.onFailure {
                steps.append(" |").append(label).append(":FAIL ").append(it.javaClass.simpleName)
            }
        }

        if (registrars.isNotEmpty()) {
            runCatching {
                holderField?.set(null, null) ?: error("holder missing")
                val initMethod = ctxClass.declaredMethods.firstOrNull { m ->
                    java.lang.reflect.Modifier.isStatic(m.modifiers) &&
                        m.parameterCount == 2 &&
                        m.parameterTypes[0] == Context::class.java &&
                        List::class.java.isAssignableFrom(m.parameterTypes[1])
                } ?: error("init(Context,List) not found")
                initMethod.isAccessible = true
                initMethod.invoke(null, context, registrars)
                steps.append(",reinit=ok")
            }.onFailure {
                steps.append(",reinit=ERR:").append(it.javaClass.simpleName)
                    .append(':').append((it.message ?: "").take(100))
            }
        } else {
            steps.append(",reinit=skip")
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

        // manifest 字符串常量（组件保名，不受 R8 影响）。
        const val DISCOVERY_SERVICE =
            "com.google.mlkit.common.internal.MlKitComponentDiscoveryService"
        const val REGISTRAR_PREFIX = "com.google.firebase.components:"
    }
}
