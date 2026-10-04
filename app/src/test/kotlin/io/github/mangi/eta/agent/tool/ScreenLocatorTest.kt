package io.github.mangi.eta.agent.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * locate_on_screen 的匹配核心（纯函数，本地 aarch64 可直接跑，不依赖 Robolectric）。
 * 真机背景：2026-10-04 conv-e7b0a537，模型在稀疏树下目测 4 连不中，
 * locate 必须一次给出正确 bbox。
 */
class ScreenLocatorTest {

    @Test
    fun exactTextMatchReturnsCenteredBbox() {
        val matches = ScreenLocator.locate(
            listOf(candidate(index = 7, text = "打开", l = 120, t = 2800, r = 320, b = 2914)),
            query = "打开",
        )
        assertEquals(1, matches.size)
        val match = matches.single()
        assertEquals(7, match.index)
        assertEquals(220, match.centerX)
        assertEquals(2857, match.centerY)
        assertEquals(120, match.left)
        assertEquals(2914, match.bottom)
        assertEquals(0.98, match.score, 0.001)
        assertEquals("text", match.field)
    }

    @Test
    fun multiTokenQueryRequiresAllTokensOnOneNode() {
        val target = candidate(index = 1, text = "COSMemory 内存管理", viewId = "com.ksu:id/open_btn")
        val other = candidate(index = 2, text = "COSMemory")
        val matches = ScreenLocator.locate(listOf(other, target), query = "cosmemory 内存")
        assertEquals(1, matches.size)
        assertEquals(1, matches.single().index)
    }

    @Test
    fun viewIdLeafExactOutranksTextContains() {
        val textHit = candidate(index = 1, text = "open_btn 备用入口")
        val viewIdHit = candidate(index = 2, text = "打开", viewId = "com.ksu:id/open_btn")
        val matches = ScreenLocator.locate(listOf(textHit, viewIdHit), query = "open_btn")
        assertEquals(2, matches.size)
        assertEquals(2, matches[0].index)
        assertEquals("viewId", matches[0].field)
        assertEquals(1.0, matches[0].score, 0.001)
        assertTrue(matches[0].score > matches[1].score)
    }

    @Test
    fun latinQueryIsCaseInsensitive() {
        val matches = ScreenLocator.locate(
            listOf(candidate(index = 0, text = "KernelSU")),
            query = "kernelsu",
        )
        assertEquals(1, matches.size)
    }

    @Test
    fun emptyBoundsAreSkipped() {
        val matches = ScreenLocator.locate(
            listOf(candidate(index = 0, text = "打开", l = 0, t = 0, r = 0, b = 0)),
            query = "打开",
        )
        assertTrue(matches.isEmpty())
    }

    @Test
    fun noMatchAndBlankQueryReturnEmptyList() {
        val nodes = listOf(candidate(index = 0, text = "设置"))
        assertTrue(ScreenLocator.locate(nodes, query = "打开").isEmpty())
        assertTrue(ScreenLocator.locate(nodes, query = "   ").isEmpty())
        assertTrue(ScreenLocator.locate(emptyList(), query = "设置").isEmpty())
    }

    @Test
    fun exactBeatsContainsAndLimitCaps() {
        val contains = candidate(index = 0, text = "打开方式")
        val exact = candidate(index = 1, text = "打开")
        val second = candidate(index = 2, text = "打开")
        val many = (3 until 20).map { candidate(index = it, text = "打开") }
        val matches = ScreenLocator.locate(listOf(contains, exact, second) + many, query = "打开")
        assertEquals(8, matches.size)
        assertEquals(1, matches[0].index)
        assertEquals(2, matches[1].index)
        assertEquals(0.98, matches[0].score, 0.001)
        assertEquals(8, matches[7].index)
    }

    private fun candidate(
        index: Int,
        text: String = "",
        desc: String = "",
        viewId: String = "",
        l: Int = 0,
        t: Int = 0,
        r: Int = 100,
        b: Int = 100,
        clickable: Boolean = true,
    ) = ScreenLocator.Candidate(
        index = index, text = text, desc = desc, viewId = viewId,
        left = l, top = t, right = r, bottom = b, clickable = clickable,
    )
}
