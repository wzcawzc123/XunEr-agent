package io.github.mangi.eta.agent.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroguardCommandTest {
    @Test
    fun blankOrUnknownOpFallsBackToAxml() {
        assertEquals("androguard axml '/sdcard/app.apk'", AndroguardCommand.build("", "/sdcard/app.apk", ""))
        assertEquals("androguard axml '/sdcard/app.apk'", AndroguardCommand.build("unknown", "/sdcard/app.apk", ""))
        assertEquals("androguard axml '/sdcard/app.apk'", AndroguardCommand.build("axml", "/sdcard/app.apk", ""))
    }

    @Test
    fun allowedOpPassedAndNormalized() {
        assertEquals("androguard apkid '/sdcard/app.apk'", AndroguardCommand.build("apkid", "/sdcard/app.apk", ""))
        assertEquals("androguard sign '/sdcard/app.apk'", AndroguardCommand.build("SIGN", "/sdcard/app.apk", ""))
    }

    @Test
    fun extraArgsAppendedAfterOp() {
        assertEquals(
            "androguard axml --no-progress '/sdcard/app.apk'",
            AndroguardCommand.build("axml", "/sdcard/app.apk", "--no-progress"),
        )
        assertEquals(
            "androguard axml -v '/sdcard/app.apk'",
            AndroguardCommand.build("axml", "/sdcard/app.apk", " -v "),
        )
    }

    @Test
    fun shellQuoteEscapesSingleQuote() {
        assertEquals("'it'\\''s.apk'", AndroguardCommand.shellQuote("it's.apk"))
        assertEquals("'/sdcard/my app.apk'", AndroguardCommand.shellQuote("/sdcard/my app.apk"))
    }

    @Test
    fun dexScanBuildsVenvPythonScriptWithLimit() {
        val cmd = AndroguardCommand.buildDexScan("/sdcard/app.apk", 25)
        assertTrue(cmd.startsWith("/opt/eta/uv-tools/androguard/bin/python -c '"))
        assertTrue(cmd.contains("androguard.core.dex"))
        assertTrue(cmd.contains("'/sdcard/app.apk' 25"))
    }

    @Test
    fun dexScanClampsLimitAndDetectsOp() {
        assertTrue(AndroguardCommand.isDexScan("dex_strings"))
        assertTrue(AndroguardCommand.isDexScan(" DEX_STRINGS "))
        assertTrue(!AndroguardCommand.isDexScan("axml"))
        assertTrue(AndroguardCommand.buildDexScan("/sdcard/app.apk", 9999).contains(" 200"))
    }
    @Test
    fun extraArgsWithShellMetacharactersAreDropped() {
        // 命令注入尝试：含 shell 元字符的参数段整个丢弃，只保留安全参数。
        assertEquals(
            "androguard axml '/sdcard/app.apk'",
            AndroguardCommand.build("axml", "/sdcard/app.apk", "--no-progress; rm -rf /"),
        )
        assertEquals(
            "androguard axml '/sdcard/app.apk'",
            AndroguardCommand.build("axml", "/sdcard/app.apk", "-o \$(whoami)"),
        )
        assertEquals(
            "androguard axml '/sdcard/app.apk'",
            AndroguardCommand.build("axml", "/sdcard/app.apk", "x;echo pwned"),
        )
    }

    @Test
    fun sanitizeExtraArgsAllowsSafeCliCharsAndDropsUnsafe() {
        assertEquals(" --no-progress", AndroguardCommand.sanitizeExtraArgs("--no-progress"))
        assertEquals(" -v", AndroguardCommand.sanitizeExtraArgs(" -v "))
        assertEquals("", AndroguardCommand.sanitizeExtraArgs("--no-progress; rm"))
        assertEquals("", AndroguardCommand.sanitizeExtraArgs(""))
        assertEquals("", AndroguardCommand.sanitizeExtraArgs("   "))
    }
}
