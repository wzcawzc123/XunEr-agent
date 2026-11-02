package io.github.mangi.eta.agent.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 命令构造的契约测试。
 *
 * 这层是**安全边界**：`androguard_analyze` 被当只读工具放行，
 * 所以只要能透传任意参数，`-o /sdcard/out.xml` 就能借它写文件。
 * 下面几条断言钉住的就是这个边界，改白名单必须同步改这里。
 */
class AndroguardCommandTest {
    @Test
    fun unknownOperationFallsBackToDefault() {
        assertEquals("axml", AndroguardCommand.normalizeOperation("evil"))
        assertEquals("axml", AndroguardCommand.normalizeOperation(null))
        assertEquals("axml", AndroguardCommand.normalizeOperation("  "))
        // 大小写与空白归一
        assertEquals("sign", AndroguardCommand.normalizeOperation(" SIGN "))
    }

    @Test
    fun onlyDexStringsIsTreatedAsInlineScript() {
        assertTrue(AndroguardCommand.isDexScan("dex_strings"))
        assertTrue(AndroguardCommand.isDexScan(" DEX_STRINGS "))
        assertFalse(AndroguardCommand.isDexScan("axml"))
        assertFalse(AndroguardCommand.isDexScan(null))
    }

    @Test
    fun buildsWhitelistedCliCommand() {
        assertEquals("androguard apkid '/sdcard/a.apk'", AndroguardCommand.build("apkid", "/sdcard/a.apk", ""))
        // 未知 operation 回落到 axml，而不是把原样拼进命令行
        assertEquals("androguard axml '/sdcard/a.apk'", AndroguardCommand.build("rm -rf", "/sdcard/a.apk", ""))
    }

    @Test
    fun rejectsAnyTokenOutsideReadOnlyWhitelist() {
        // -o / --dump 会写文件：整段丢弃，而不是只删掉那个 token
        assertEquals("", AndroguardCommand.sanitizeExtraArgs("-o /sdcard/out.xml"))
        assertEquals("", AndroguardCommand.sanitizeExtraArgs("--dump out.xml"))
        // 混入一个非法 token 也整段丢弃，避免"剩下一半参数"产生意外语义
        assertEquals("", AndroguardCommand.sanitizeExtraArgs("-a -o /tmp/x"))
        // 白名单内的只读分析开关保留
        assertEquals(" -a -s", AndroguardCommand.sanitizeExtraArgs("-a -s"))
        assertEquals(" --version", AndroguardCommand.sanitizeExtraArgs("  --version  "))
        assertEquals("", AndroguardCommand.sanitizeExtraArgs("   "))
    }

    @Test
    fun quotesSingleQuotesAndSpaces() {
        assertEquals("'/sdcard/a b.apk'", AndroguardCommand.shellQuote("/sdcard/a b.apk"))
        // POSIX：单引号内无法出现单引号，用 '\'' 收尾再续
        assertEquals("'it'\\''s.apk'", AndroguardCommand.shellQuote("it's.apk"))
    }

    @Test
    fun dexScanClampsLimitAndUsesVenvPython() {
        val low = AndroguardCommand.buildDexScan("/sdcard/a.apk", 0)
        val high = AndroguardCommand.buildDexScan("/sdcard/a.apk", 9_999)

        assertTrue(low.contains("/opt/eta/uv-tools/androguard/bin/python"))
        assertTrue(low.contains("'/sdcard/a.apk' 1"))
        assertTrue(high.contains("'/sdcard/a.apk' 200"))
    }
}
