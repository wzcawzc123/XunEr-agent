package io.github.mangi.eta.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M3.3 诊断导出报告的表驱动锁：
 * - 整段脱敏（按 AgentSensitiveToolPolicy 单一真源，非逐字段判断）
 * - 错误码/系统事件汇总来自持久化行，不谎报无记录的硬裁剪
 */
class EtaDiagnosticsReportTest {

    private fun fixture() = EtaDiagnosticsReport.build(
        generatedAt = 1_760_000_000_000L,
        appVersion = "3.7.0-test",
        deviceInfo = "TestModel / SDK 36",
        conversations = listOf(
            EtaDiagnosticsReport.Conversation(
                title = "诊断样例",
                updatedAt = 1_760_000_000_000L,
                messageCount = 42,
                rows = listOf(
                    EtaDiagnosticsReport.Row(
                        sortIndex = 10, type = "tool", toolName = "observe_screen",
                        toolStatus = "Success",
                        argumentsSummary = "观察屏幕 · 含界面树", resultSummary = "完成",
                    ),
                    EtaDiagnosticsReport.Row(
                        sortIndex = 12, type = "tool", toolName = "get_logcat",
                        toolStatus = "Success",
                        argumentsSummary = "参数里含密码 SECRET-PASSWORD",
                        resultSummary = "结果里含验证码 884421",
                    ),
                    EtaDiagnosticsReport.Row(
                        sortIndex = 14, type = "tool", toolName = "locate_on_screen",
                        toolStatus = "Failed",
                        argumentsSummary = "定位元素 · 打开",
                        resultSummary = "失败 · 树与 OCR 都没有匹配 code=LOCATE_MISS",
                    ),
                    EtaDiagnosticsReport.Row(
                        sortIndex = 20, type = "system_notice",
                        content = "context_compaction",
                        resultSummary = "上下文已压缩：约 84,377 → 72,915 tokens",
                    ),
                ),
            ),
        ),
    )

    @Test
    fun sensitiveToolPreviewsAreRedactedWholesale() {
        val report = fixture()
        assertFalse("敏感参数原文泄漏", report.contains("SECRET-PASSWORD"))
        assertFalse("敏感结果原文泄漏", report.contains("884421"))
        assertTrue(report.contains("[敏感工具，已脱敏]"))
        // 非敏感工具的预览保留
        assertTrue(report.contains("观察屏幕 · 含界面树"))
    }

    @Test
    fun failedCodesAndSystemEventsAreTallied() {
        val report = fixture()
        assertTrue(report.contains("LOCATE_MISS × 1"))
        assertTrue(report.contains("context_compaction × 1"))
        assertTrue(report.contains("上下文已压缩"))
    }

    @Test
    fun headerCarriesAppVersionDeviceAndPrivacyNote() {
        val report = fixture()
        assertTrue(report.contains("app: 3.7.0-test"))
        assertTrue(report.contains("TestModel / SDK 36"))
        assertTrue(report.contains("硬裁剪无持久记录"))
    }
}
