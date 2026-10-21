package io.github.mangi.eta.agent.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 记忆预览切分的回归测试。
 *
 * 背景：记忆页把整个 MEMORY.md（真实 17 万字符）塞进一个可滚动 Text，
 * Compose 全量测量导致进入页面白屏半秒以上。修复方式是**预览只渲染核心区**。
 *
 * 这里锁住三件事：
 * 1. 有归档区时必须切掉，否则白屏问题会回归；
 * 2. 没有归档区时不能多切（否则用户会以为内容丢了）；
 * 3. 结构不认识时原样返回（旧文件、用户手改过的格式都不能崩）。
 */
class AgentMemoryPreviewTest {

    private val content = buildString {
        appendLine("# 核心记忆")
        appendLine("## 环境")
        appendLine("- Linux 工具环境：Debian 13")
        appendLine("## 规则")
        appendLine("- 署名用 -是你吗薰儿")
        appendLine("# 一加11 触控采样率战役")
        appendLine("## 终局")
        appendLine("- v2.2.1")
        appendLine("# 振动模块战役")
        appendLine("- 已废除")
    }

    @Test
    fun `切掉归档区只保留核心区`() {
        val slice = AgentMemoryPreview.slice(content)
        assertTrue("有归档区时必须标记截断", slice.truncated)
        assertTrue(slice.text.contains("## 环境"))
        assertTrue(slice.text.contains("## 规则"))
        assertFalse("归档章节不能出现在预览里", slice.text.contains("触控采样率"))
        assertFalse(slice.text.contains("振动模块"))
    }

    @Test
    fun `归档区字符数不为零`() {
        val slice = AgentMemoryPreview.slice(content)
        assertTrue("应统计出归档区大小，供 UI 提示", slice.archivedChars > 0)
    }

    @Test
    fun `只有核心区时不截断`() {
        val onlyCore = buildString {
            appendLine("# 核心记忆")
            appendLine("## 环境")
            appendLine("- Debian 13")
        }
        val slice = AgentMemoryPreview.slice(onlyCore)
        assertFalse(slice.truncated)
        assertEquals(0, slice.archivedChars)
        assertTrue(slice.text.contains("Debian 13"))
    }

    @Test
    fun `没有核心区标记时原样返回`() {
        // 旧文件或用户手改过的结构：不能猜，也不能吞内容
        val raw = "# 我的笔记\n- 随便写的\n# 另一节\n- 内容"
        val slice = AgentMemoryPreview.slice(raw)
        assertFalse(slice.truncated)
        assertEquals(raw, slice.text)
    }

    @Test
    fun `空内容安全返回`() {
        listOf("", "   ", "\n").forEach { blank ->
            val slice = AgentMemoryPreview.slice(blank)
            assertFalse(slice.truncated)
            assertEquals(blank, slice.text)
        }
    }

    @Test
    fun `二级标题不会提前结束核心区`() {
        // `## 环境` 不能被当成一级标题把核心区切断
        val slice = AgentMemoryPreview.slice(content)
        assertTrue("核心区应包含全部 ## 小节", slice.text.contains("## 规则"))
    }
}
