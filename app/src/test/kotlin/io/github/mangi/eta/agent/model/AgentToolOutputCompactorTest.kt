package io.github.mangi.eta.agent.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolOutputCompactorTest {

    @Test
    fun shortOutputIsUntouched() {
        val short = "ok".repeat(100)
        assertEquals(short, AgentToolOutputCompactor.compact(short))
    }

    @Test
    fun longOutputKeepsHeadAndTailAndMarksOmission() {
        val head = "START".repeat(400)          // 2000 chars
        val middle = "m".repeat(20_000)
        val tail = "END".repeat(700)            // 2100 chars > KEEP_TAIL，确保尾巴不含中间段
        val content = head + middle + tail
        val compacted = AgentToolOutputCompactor.compact(content)

        assertTrue(compacted.length < content.length)
        assertTrue(compacted.contains("已省略"))
        assertTrue(compacted.contains("START"))
        assertTrue(compacted.contains("END"))
        // 中间的大段被拿走，头尾边界正确
        assertFalse(compacted.contains("mmmmmmmmmmmm"))
    }

    @Test
    fun errorLinesAreSurfaced() {
        val content = "normal line\n" +
            "Exception in thread \"main\": java.lang.NullPointerException\n".repeat(1) +
            "m".repeat(15_000) +
            "\nE/AndroidRuntime: FATAL EXCEPTION: main\n"
        val compacted = AgentToolOutputCompactor.compact(content)
        assertTrue(compacted.contains("NullPointerException"))
        assertTrue(compacted.contains("FATAL EXCEPTION"))
    }

    @Test
    fun noErrorLinesYieldsNoErrorSection() {
        val content = "aaaa".repeat(4_000)
        val compacted = AgentToolOutputCompactor.compact(content)
        assertFalse(compacted.contains("关键错误"))
    }

    @Test
    fun omissionMarkerReportsApproximateLineCount() {
        val content = buildString {
            repeat(9_999) { append("line $it\n") }
        }
        val compacted = AgentToolOutputCompactor.compact(content)
        assertTrue(compacted.contains("共约 10000 行"))
    }
}
