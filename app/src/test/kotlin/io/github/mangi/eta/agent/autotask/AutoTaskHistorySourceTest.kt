package io.github.mangi.eta.agent.autotask

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 「自动任务该用哪份历史」的回归测试。
 *
 * **锁死一个真机 bug**：离线执行路径原本读 `conversations.history_json`，
 * 而真实数据里这张表存的是 `[]` —— 模型看到零上下文、前缀与正常对话不一致、
 * 缓存 100% 击穿。而主路径（投递给 UI）一直是对的，所以长期没被发现。
 */
class AutoTaskHistorySourceTest {

    private val checkpoint = """[{"role":"user","content":"真实历史"}]"""
    private val conversation = """[{"role":"user","content":"会话表里的历史"}]"""

    @Test
    fun `优先使用 checkpoint`() {
        assertEquals(
            "checkpoint 才是发给模型的历史，必须优先",
            checkpoint,
            AutoTaskHistorySource.pick(checkpoint, conversation),
        )
    }

    @Test
    fun `checkpoint 为空数组时回退到会话表`() {
        // 这正是真机上的实际数据：conversations.history_json = "[]"
        assertEquals(
            conversation,
            AutoTaskHistorySource.pick("[]", conversation),
        )
    }

    @Test
    fun `checkpoint 缺失时回退`() {
        assertEquals(conversation, AutoTaskHistorySource.pick(null, conversation))
    }

    @Test
    fun `checkpoint 为空白时回退`() {
        listOf("", "   ", "\n").forEach { blank ->
            assertEquals(
                "空白 checkpoint 不能当成有效历史",
                conversation,
                AutoTaskHistorySource.pick(blank, conversation),
            )
        }
    }

    @Test
    fun `两者都没有内容时返回 null`() {
        // 空历史必须表现为 null，让调用方明确走"无上下文"分支，
        // 而不是解码出空列表却以为加载成功。
        assertNull(AutoTaskHistorySource.pick(null, null))
        assertNull(AutoTaskHistorySource.pick("[]", "[]"))
        assertNull(AutoTaskHistorySource.pick("  ", ""))
    }

    @Test
    fun `绝不能把空数组当成有效历史`() {
        // 回归断言：若哪天有人把 usable() 改成只看 isNotBlank，
        // "[]" 会被当成有效值 → 又回到"零上下文"的老 bug。
        assertEquals(
            conversation,
            AutoTaskHistorySource.pick("[]", conversation),
        )
        assertNull(AutoTaskHistorySource.pick("[]", null))
    }

    @Test
    fun `两侧都有内容时不会拼接或混用`() {
        // 语义是"择一"，不是"合并"：混用会导致前缀与正常对话不一致。
        val picked = AutoTaskHistorySource.pick(checkpoint, conversation)
        assertEquals(checkpoint, picked)
    }
}
