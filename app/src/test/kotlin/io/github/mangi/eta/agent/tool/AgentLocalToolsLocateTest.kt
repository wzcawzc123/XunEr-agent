package io.github.mangi.eta.agent.tool

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import io.github.mangi.eta.agent.device.RootShellDeviceController
import io.github.mangi.eta.agent.media.AgentImageCodec
import io.github.mangi.eta.agent.media.RecognizedText
import io.github.mangi.eta.agent.media.ScreenTextRecognizer
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentScreenObservationContract
import io.github.mangi.eta.core.AgentLogger
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * locate_on_screen 端到端（provider 注入，CI 侧 Robolectric 验证）。
 * 对应 2026-10-04 真机取证：稀疏树下目测 4 连不中，locate 必须一次给出
 * 与真值 (217,2857) 同级的精确 center，并与 observe 同一套发布/新鲜度记账。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AgentLocalToolsLocateTest {

    @Test
    fun locateReturnsScreenSpaceCenterAndRequestsFreshTreeWithoutScreenshot() {
        var received: AgentScreenObservationContract.Options? = null
        val tools = tools { options ->
            received = options
            observation("o-loc", nodes = listOf(node(7, "打开")))
        }
        val result = tools.execute(call("""{"query":"打开"}"""))
        val payload = JSONObject(result.content)

        assertTrue(payload.getBoolean("ok"))
        assertEquals("locate_on_screen", payload.getString("tool"))
        assertEquals("o-loc", payload.getString("observation_id"))
        assertEquals("screen", payload.getString("coordinate_space"))
        assertEquals(1, payload.getInt("match_count"))
        assertEquals(1, payload.getInt("node_count"))
        val match = payload.getJSONArray("matches").getJSONObject(0)
        assertEquals(220, match.getInt("center_x"))
        assertEquals(2857, match.getInt("center_y"))
        assertEquals(200, match.getInt("w"))
        assertEquals(114, match.getInt("h"))
        assertEquals(7, match.getInt("node_index"))
        assertTrue(match.getBoolean("clickable"))

        assertFalse(checkNotNull(received).includeScreenshot)
        assertTrue(checkNotNull(received).includeUiTree)
        assertEquals(120, checkNotNull(received).maxNodes)
        tools.close()
    }

    @Test
    fun locateMissReturnsStructuredCodeInsteadOfCoordinates() {
        val tools = tools { observation("o-miss", nodes = listOf(node(1, "设置"))) }
        val payload = JSONObject(tools.execute(call("""{"query":"打开"}""")).content)
        assertFalse(payload.getBoolean("ok"))
        assertEquals("LOCATE_MISS", payload.getString("code"))
        tools.close()
    }

    @Test
    fun emptyTreeReturnsUnavailableInsteadOfGuessing() {
        val tools = tools { observation("o-empty", nodes = emptyList()) }
        val payload = JSONObject(tools.execute(call("""{"query":"打开"}""")).content)
        assertFalse(payload.getBoolean("ok"))
        assertEquals("LOCATE_UNAVAILABLE", payload.getString("code"))
        tools.close()
    }

    @Test
    fun blankQueryIsRejectedBeforeAnySnapshot() {
        var providerCalled = false
        val tools = tools {
            providerCalled = true
            observation("o-blank", nodes = listOf(node(1, "打开")))
        }
        val payload = JSONObject(tools.execute(call("""{"query":"  "}""")).content)
        assertEquals("INVALID_ARGUMENT", payload.getString("code"))
        assertFalse("空 query 不应触发快照抓取", providerCalled)
        tools.close()
    }

    @Test
    fun treeMissFallsBackToOcrAndScalesCenterToScreen() {
        // M2.1 OCR 通道：树里没有 ≠ 屏幕上没有（KSU Compose 列表实证场景）。
        val bitmap = Bitmap.createBitmap(1440, 3216, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(0x10, 0x20, 0x30))
        val image = try {
            AgentImageCodec.fromScreenBitmap(bitmap, source = "screen")
        } finally {
            bitmap.recycle()
        }
        val tools = AgentLocalTools(
            context = RuntimeEnvironment.getApplication() as Context,
            logger = NoOpLogger,
            rootAvailable = { false },
            screenObservationProvider = { options ->
                if (options.includeScreenshot) observation("o-shot", nodes = emptyList(), image = image)
                else observation("o-tree-empty", nodes = emptyList())
            },
            textRecognizerFactory = {
                object : ScreenTextRecognizer {
                    override fun recognize(bitmap: Bitmap): List<RecognizedText> =
                        listOf(RecognizedText("打开", Rect(170, 2800, 270, 2914)))
                }
            },
        )

        val payload = JSONObject(tools.execute(call("""{"query":"打开"}""")).content)
        assertTrue("payload=$payload", payload.getBoolean("ok"))
        assertEquals("payload=$payload", "ocr", payload.getString("source"))
        assertEquals("payload=$payload", 1, payload.getInt("match_count"))
        assertTrue("payload=$payload", payload.getString("note").contains("OCR"))
        // 坐标断言用载荷自带的 image/screen 尺寸自洽计算（Robolectric 屏幕尺寸不可预知）
        val scale = payload.getInt("screen_width").toFloat() / payload.getInt("image_width")
        val match = payload.getJSONArray("matches").getJSONObject(0)
        assertEquals((220f * scale).toInt(), match.getInt("center_x"))
        assertEquals((2857f * scale).toInt(), match.getInt("center_y"))
        assertEquals(0, match.getInt("node_index"))
        tools.close()
    }

    private fun call(args: String) = AgentModelClient.ToolCall(
        id = "call-locate",
        name = "locate_on_screen",
        argumentsJson = args,
    )

    private fun node(index: Int, text: String) = RootShellDeviceController.UiNode(
        index = index,
        text = text,
        desc = "",
        className = "android.widget.Button",
        packageName = "com.ksu",
        viewId = "com.ksu:id/open",
        bounds = Rect(120, 2800, 320, 2914),
        clickable = true,
        longClickable = false,
        scrollable = false,
        focused = false,
        editable = false,
        password = false,
        enabled = true,
    )

    private fun observation(
        id: String,
        nodes: List<RootShellDeviceController.UiNode>,
        image: AgentModelClient.ModelImage? = null,
    ): RootShellDeviceController.Observation =
        RootShellDeviceController.Observation(
            content = """{"ok":true,"tool":"observe_screen","observation_id":"$id"}""",
            image = image,
            elementObservation = RootShellDeviceController.ElementObservation(
                id = id,
                source = RootShellDeviceController.ElementSource.ACCESSIBILITY,
                packageName = "com.ksu",
                windowId = 1,
                nodes = nodes,
                maxNodes = 120,
                truncated = false,
            ),
            coordinateSpace = null,
        )

    private fun tools(
        provider: (AgentScreenObservationContract.Options) -> RootShellDeviceController.Observation,
    ): AgentLocalTools = AgentLocalTools(
        context = RuntimeEnvironment.getApplication() as Context,
        logger = NoOpLogger,
        rootAvailable = { false },
        screenObservationProvider = provider,
    )

    private object NoOpLogger : AgentLogger {
        override fun debug(message: () -> String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, throwable: Throwable?) = Unit
    }
}
