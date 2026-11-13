package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.model.AgentModelClient
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 编辑重发前缀守卫的估算口径。
 *
 * 这里错的方向只有两种，都有代价：
 * - 估太小 → 漏放必败请求（Provider 直接拒绝，白烧一次失败与退避）
 * - 估太大 → 误拦合法操作（用户点了编辑重发却发不出去）
 * 所以口径要固定、可回归：ASCII 3 字 ≈ 1 token（向上取整），非 ASCII 1 字 ≈ 1 token。
 */
class AgentHistoryPrefixGuardTest {

    @Test
    fun asciiRoundsUp() {
        assertEquals(0, textTokens(""))
        assertEquals(1, textTokens("abc"))
        // 4 个 ASCII = 1.33 token；向上取整到 2，宁可略保守
        assertEquals(2, textTokens("abcd"))
        assertEquals(2, textTokens("abcdef"))
        assertEquals(3, textTokens("abcdefg"))
    }

    @Test
    fun nonAsciiCountsAsOneTokenPerChar() {
        assertEquals(3, textTokens("中文测试".take(3)))
        assertEquals(6, textTokens("六个汉字测试"))
    }

    @Test
    fun mixedContentAddsUp() {
        // "abc" = 1 token + "中文" = 2 token
        assertEquals(3, textTokens("abc中文"))
    }

    @Test
    fun surrogatePairCountsAsSingleToken() {
        // emoji 在 UTF-16 里占两个 char，但按 code point 计只有一个字符。
        // 若用 text.length 会算成 2 token，长串表情会显著高估前缀体积。
        assertEquals(1, textTokens("😀"))
        assertEquals(3, textTokens("😀😀😀"))
    }

    @Test
    fun historySumsContentAndReasoning() {
        val messages = listOf(
            AgentModelClient.ConversationMessage(role = "user", content = "abc"),
            AgentModelClient.ConversationMessage(
                role = "assistant",
                content = "中文",
                reasoningContent = "abcd",
            ),
        )
        // 1 + (2 + 2)
        assertEquals(5, estimateHistoryTokens(messages))
    }

    @Test
    fun emptyHistoryIsZero() {
        assertEquals(0, estimateHistoryTokens(emptyList()))
        assertEquals(
            0,
            estimateHistoryTokens(listOf(AgentModelClient.ConversationMessage(role = "user"))),
        )
    }

    /**
     * 窗口取值：会话绑定的模型优先于全局选中模型。
     *
     * run 用的是 effectiveModelId，而 loadConfig() 读的是全局运行时配置——
     * 两者窗口不同时，守卫会误拦或漏拦。
     */
    @Test
    fun guardWindowPrefersConversationModel() {
        assertEquals(32_000, guardContextWindow(bound = 32_000, global = 200_000))
        assertEquals(200_000, guardContextWindow(bound = null, global = 200_000))
        // 非法窗口（0 / 负数）不当作有效值，继续回退
        assertEquals(200_000, guardContextWindow(bound = 0, global = 200_000))
        assertEquals(128_000, guardContextWindow(bound = null, global = null))
    }
}
