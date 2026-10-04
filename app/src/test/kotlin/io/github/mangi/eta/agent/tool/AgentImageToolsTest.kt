package io.github.mangi.eta.agent.tool

import android.graphics.Bitmap
import android.graphics.Color
import io.github.mangi.eta.agent.device.BoundedRootCommandExecutor
import io.github.mangi.eta.core.AgentLogger
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * F7 不变量锁（docs/INVARIANTS.md）：read_image 结果必须携带原图像素尺寸
 * image_width/image_height，并附“与 coordinate_contract 不一致时禁止用于推算
 * 点击坐标”的提示。CI（ubuntu）侧验证；本地 aarch64 跑不了 Robolectric。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AgentImageToolsTest {

    @Test
    fun readImageReportsPixelDimensionsAndCoordinateWarning() {
        val context = RuntimeEnvironment.getApplication()
        val png = File(context.cacheDir, "f7-dim.png")
        val bitmap = Bitmap.createBitmap(960, 2160, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(0x33, 0x66, 0x99))
        try {
            png.outputStream().use { stream ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
            }
        } finally {
            bitmap.recycle()
        }
        val executor = BoundedRootCommandExecutor(logger = SilentLogger, rootAvailable = { false })
        try {
            val tools = AgentImageTools(context, executor, rootAvailable = { false })
            val result = tools.readImage(JSONObject().put("path", png.absolutePath))

            val payload = JSONObject(result.content)
            assertTrue(payload.getBoolean("ok"))
            assertEquals("read_image", payload.getString("tool"))
            assertEquals(960, payload.getInt("image_width"))
            assertEquals(2160, payload.getInt("image_height"))
            assertTrue(payload.getBoolean("image_attached"))
            assertTrue(
                "缺少坐标使用警告",
                payload.getString("note").contains("禁止用于推算点击坐标"),
            )
            assertEquals(1, result.images.size)
        } finally {
            executor.close()
            png.delete()
        }
    }

    @Test
    fun readImageRejectsRelativePathsWithoutTouchingDisk() {
        val context = RuntimeEnvironment.getApplication()
        val executor = BoundedRootCommandExecutor(logger = SilentLogger, rootAvailable = { false })
        try {
            val tools = AgentImageTools(context, executor, rootAvailable = { false })
            val result = tools.readImage(JSONObject().put("path", "relative/path.png"))
            val payload = JSONObject(result.content)
            assertEquals("IMAGE_PATH_DENIED", payload.getString("code"))
        } finally {
            executor.close()
        }
    }

    private object SilentLogger : AgentLogger {
        override fun debug(message: () -> String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, throwable: Throwable?) = Unit
    }
}
