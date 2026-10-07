package io.github.mangi.eta.agent.tool

import android.content.Context
import io.github.mangi.eta.agent.model.AgentModelClient
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

/**
 * `terminal` 的 environment 入参不再静默兜底。
 *
 * 背景：原先 `optString("environment", "android")` 会把「模型没传环境」伪装成「参数正常」，
 * 实测有会话据此把它误判成 harness 剥离参数，进而放弃重试、绕道手搓 zip。
 * 校验发生在调用 terminalController **之前**，因此前两条用例不需要真实的终端会话。
 *
 * 大小写：底层 `RootShellTerminalController.normalizeEnvironment()` 用 `.lowercase()` 解析，
 * 上层判定必须与之一致 —— 曾做区分大小写的字面比较，"Linux" 会被误拒（而 3.4.0 正常）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentTerminalEnvironmentArgumentTest {
    @Test
    fun unrecognizedEnvironmentIsRejectedInsteadOfSilentlyFallingBackToAndroid() {
        tools().use { tools ->
            val result = tools.execute(terminalCall(environment = "posix"))

            val payload = JSONObject(result.content)
            assertFalse("不可识别的 environment 不能被静默当成 android", payload.getBoolean("ok"))
            assertEquals("INVALID_ARGUMENT", payload.getString("code"))
        }
    }

    @Test
    fun rejectionEchoesTheReceivedValueAndTheAllowedSet() {
        tools().use { tools ->
            val result = tools.execute(terminalCall(environment = "posix"))

            val message = JSONObject(result.content).getString("message")
            // 回显实际收到的值：否则调用方仍然只能猜"到底是我没发，还是链路上被改过"。
            assertTrue(message.contains("posix"))
            assertTrue(message.contains("android"))
            assertTrue(message.contains("linux"))
        }
    }

    @Test
    fun environmentArgumentIsMatchedCaseInsensitively() {
        tools().use { tools ->
            // 回归：这里曾做区分大小写的字面比较，"Linux" 会被误判成非法值，
            // 而同样的调用在 3.4.0 是正常执行的（底层本来就忽略大小写）。
            assertTrue(tools.isValidTerminalEnvironmentArgument("linux"))
            assertTrue(tools.isValidTerminalEnvironmentArgument("Linux"))
            assertTrue(tools.isValidTerminalEnvironmentArgument("LINUX"))
            assertTrue(tools.isValidTerminalEnvironmentArgument("android"))
            assertTrue(tools.isValidTerminalEnvironmentArgument("Android"))
        }
    }

    @Test
    fun missingEnvironmentIsTreatedAsNotProvided() {
        tools().use { tools ->
            // 空白表示"未提供"，由调用方按 android 兜底并在结果里标注来源，不算非法值。
            assertTrue(tools.isValidTerminalEnvironmentArgument(""))
            assertTrue(tools.isValidTerminalEnvironmentArgument("   "))
        }
    }

    @Test
    fun genuinelyUnknownEnvironmentStaysRejected() {
        tools().use { tools ->
            assertFalse(tools.isValidTerminalEnvironmentArgument("posix"))
            assertFalse(tools.isValidTerminalEnvironmentArgument("windows"))
        }
    }

    private fun terminalCall(environment: String) = AgentModelClient.ToolCall(
        id = "terminal-1",
        name = "terminal",
        argumentsJson = JSONObject()
            .put("action", "open_and_exec")
            .put("command", "id")
            .put("environment", environment)
            .toString(),
    )

    private fun tools(): AgentLocalTools = AgentLocalTools(
        context = RuntimeEnvironment.getApplication() as Context,
        logger = NoOpLogger,
        terminalToolsEnabled = { true },
    )

    private object NoOpLogger : AgentLogger {
        override fun debug(message: () -> String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, throwable: Throwable?) = Unit
    }
}
