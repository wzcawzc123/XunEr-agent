package io.github.mangi.eta.agent.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M2.3 契约锁：发出图尺寸 ≡ coordinate_contract.screenshot。
 *
 * 完整等式有三层：AgentImageCodecTest 锁"编码不缩放"（图=bitmap 原尺寸）→
 * observe() 用 image.width/height 构建 CoordinateSpace（6 行内联，构造点）→
 * buildCoordinateContract 由 CoordinateSpace 生成契约 JSON（本锁）。
 * 任何一层被改走样（例如契约改用 display 尺寸、或 scale 算反），本测试即红。
 * 背景：v3.4.8 的 STALE_RESOLUTION 判据、模型换算 screenshot↔screen 全依赖这组数值。
 */
class CoordinateContractTest {

    @Test
    fun screenshotContractMatchesEncodedImageDimensions() {
        // 2K 截图(1440x3216) + FHD 显示(1080x2412)：契约必须原样转录图尺寸，scale=screen/screenshot
        val imageWidth = 1440
        val imageHeight = 3216
        val space = RootShellDeviceController.CoordinateSpace(
            screenWidth = 1080,
            screenHeight = 2412,
            screenshotWidth = imageWidth,
            screenshotHeight = imageHeight,
        )
        val contract = buildCoordinateContract(space)

        assertEquals("screenshot", contract.getString("default_coordinate_space"))
        val screenshot = contract.getJSONObject("screenshot")
        assertEquals(imageWidth, screenshot.getInt("width"))
        assertEquals(imageHeight, screenshot.getInt("height"))
        val screen = contract.getJSONObject("screen")
        assertEquals(1080, screen.getInt("width"))
        assertEquals(2412, screen.getInt("height"))
        val scale = contract.getJSONObject("scale_to_screen")
        assertEquals(1080.0 / 1440.0, scale.getDouble("x"), 1e-9)
        assertEquals(2412.0 / 3216.0, scale.getDouble("y"), 1e-9)
        assertTrue(contract.getString("note").contains("截图像素坐标"))
    }

    @Test
    fun oneToOneCaptureKeepsScaleExactlyOne() {
        // 常态：FHD 截图在 FHD 屏上 scale 恰为 1，coordinate_contract 的 scale 1:1 说法来源于此
        val space = RootShellDeviceController.CoordinateSpace(
            screenWidth = 1080, screenHeight = 2412,
            screenshotWidth = 1080, screenshotHeight = 2412,
        )
        val scale = buildCoordinateContract(space).getJSONObject("scale_to_screen")
        assertEquals(1.0, scale.getDouble("x"), 1e-9)
        assertEquals(1.0, scale.getDouble("y"), 1e-9)
    }

    @Test
    fun noScreenshotFallsBackToScreenSpaceWithoutScaleClaim() {
        val contract = buildCoordinateContract(null)
        assertEquals("screen", contract.getString("default_coordinate_space"))
        assertTrue(contract.getString("note").contains("真实设备屏幕坐标"))
        assertTrue("无截图时不得给出 scale（否则模型会拿它换算不存在的截图坐标）",
            !contract.has("scale_to_screen"))
    }
}
