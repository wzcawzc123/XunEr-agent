package io.github.mangi.eta.ui.markdown

import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TexMathDelimitersTest {

    /**
     * 上游社区报告的死循环：围栏（```）内遇到空行时 lineEnd == index，
     * `index = lineEnd; continue` 原地踏步。文本含 \( 或 \[ 即可进入该路径。
     */
    @Test
    fun normalizeDoesNotHangOnBlankLineInsideFence() {
        val source = "```\ncode\n\n\\(x\\)\n```\n"
        val result = withTimeoutMs(2_000) { TexMathDelimiters.normalize(source) }
        // 围栏内的 \( 不应被转义为 $$。
        assertTrue(result.contains("\\("))
    }

    @Test
    fun normalizesDelimitersOutsideFence() {
        val source = "text \\(x\\) end"
        assertEquals("text \$\$x\$\$ end", TexMathDelimiters.normalize(source))
    }

    @Test
    fun skipsFencedCodeBlock() {
        val source = "```\n\\(ignored\\)\n```\noutside \\(kept\\)"
        val result = withTimeoutMs(2_000) { TexMathDelimiters.normalize(source) }
        assertTrue(result.contains("\\("))
        assertTrue(result.contains("\$\$kept\$\$"))
    }

    private fun <T> withTimeoutMs(timeoutMs: Long, block: () -> T): T {
        val holder = AtomicReference<T?>()
        val thread = Thread { holder.set(block()) }
        thread.start()
        thread.join(timeoutMs)
        assertTrue("normalize 疑似死循环（超时 $timeoutMs ms）", !thread.isAlive)
        return holder.get()!!
    }
}
