package io.github.mangi.eta.agent.autotask

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ScheduleSpec] 的时间计算测试。
 *
 * **这个文件是补审查发现的缺口**：`Daily` 曾有一个真 bug ——
 * `nextRunAtMillis` 从 `now` 往后算，返回值永远在未来，导致 `isDue()` 恒为 false，
 * 每日任务一次都跑不了。而当时这个类**没有任何测试**。
 *
 * 时间计算这类逻辑靠肉眼审不出来，必须用固定时间点做断言。
 */
class ScheduleSpecTest {

    // ---------- After（延迟一次） ----------

    @Test
    fun `after 从锚点起算而非从当前时间`() {
        val spec = ScheduleSpec.After(delayMillis = 30 * 60_000L, anchor = 1_000_000L)
        // 无论 now 是多少，目标时刻都固定在 anchor + delay
        assertEquals(1_000_000L + 1_800_000L, spec.nextRunAtMillis(now = 5_000_000L, lastRunAt = null))
        assertEquals(1_000_000L + 1_800_000L, spec.nextRunAtMillis(now = 0L, lastRunAt = null))
    }

    @Test
    fun `after 是一次性`() {
        assertFalse(ScheduleSpec.After(1000L, 0L).repeating)
    }

    @Test
    fun `after 的延迟必须为正`() {
        runCatching { ScheduleSpec.After(0L, 0L) }.let { assertTrue(it.isFailure) }
        runCatching { ScheduleSpec.After(-1L, 0L) }.let { assertTrue(it.isFailure) }
    }

    // ---------- Every（固定间隔） ----------

    @Test
    fun `every 首次等一个周期而不是立刻跑`() {
        val spec = ScheduleSpec.Every(60_000L)
        assertEquals(
            "首次应在 now + interval，避免建完立刻烧一轮",
            5_060_000L,
            spec.nextRunAtMillis(now = 5_000_000L, lastRunAt = null),
        )
    }

    @Test
    fun `every 之后以 lastRunAt 为基准`() {
        val spec = ScheduleSpec.Every(60_000L)
        assertEquals(
            "上次执行后 + 间隔",
            1_060_000L,
            spec.nextRunAtMillis(now = 9_999_999L, lastRunAt = 1_000_000L),
        )
    }

    @Test
    fun `every 是重复任务`() {
        assertTrue(ScheduleSpec.Every(1000L).repeating)
    }

    @Test
    fun `every 的间隔必须为正`() {
        assertTrue(runCatching { ScheduleSpec.Every(0L) }.isFailure)
    }

    // ---------- Daily（每天某点） ----------

    @Test
    fun `daily 从未执行时落在最近的那个时刻`() {
        val spec = ScheduleSpec.Daily(9, 0)
        val now = System.currentTimeMillis()
        val next = spec.nextRunAtMillis(now, lastRunAt = null)
        // 关键断言：返回值不能"永远是未来两天以上"，否则 isDue 恒 false
        assertTrue("next 应在 now 之后 0~1 天内", next > now - 24 * 3600_000L)
        assertTrue("next 不应超过 now 之后 1 天", next <= now + 24 * 3600_000L + 1000)
    }

    @Test
    fun `daily 新建任务不会立刻触发`() {
        val spec = ScheduleSpec.Daily(0, 0)  // 每天 00:00
        val now = System.currentTimeMillis()
        val next = spec.nextRunAtMillis(now, lastRunAt = null)
        // 语义：从未执行时等"下一个 00:00"，而不是补跑今天已经过去的那次
        assertTrue("新建任务应等到下一个时刻，不能立刻跑", next > now)
    }

    @Test
    fun `daily 执行后顺延到下一次`() {
        val spec = ScheduleSpec.Daily(9, 0)
        val now = System.currentTimeMillis()
        // 假设刚在"现在"跑过 → 下一次必须是未来
        val next = spec.nextRunAtMillis(now, lastRunAt = now)
        assertTrue("刚执行过，下一次应在未来", next > now)
        assertTrue("但不应超过 1 天 + 少量余量", next <= now + 24 * 3600_000L + 5000)
    }

    @Test
    fun `daily 是重复任务`() {
        assertTrue(ScheduleSpec.Daily(9, 0).repeating)
    }

    @Test
    fun `daily 的小时分钟超范围时拒绝构造`() {
        assertTrue(runCatching { ScheduleSpec.Daily(24, 0) }.isFailure)
        assertTrue(runCatching { ScheduleSpec.Daily(0, 60) }.isFailure)
        assertTrue(runCatching { ScheduleSpec.Daily(-1, 0) }.isFailure)
    }

    // ---------- 描述文案 ----------

    @Test
    fun `时长格式化可读`() {
        assertEquals("30 分钟", ScheduleSpec.formatDuration(30 * 60_000L))
        assertEquals("2 小时", ScheduleSpec.formatDuration(2 * 3600_000L))
        assertEquals("1 天", ScheduleSpec.formatDuration(24 * 3600_000L))
        assertEquals("1 小时 30 分钟", ScheduleSpec.formatDuration(90 * 60_000L))
        assertEquals("不到 1 分钟", ScheduleSpec.formatDuration(30_000L))
    }

    @Test
    fun `describe 覆盖三种类型`() {
        assertEquals("创建后 30 分钟", ScheduleSpec.After(30 * 60_000L, 0L).describe())
        assertEquals("每 2 小时", ScheduleSpec.Every(2 * 3600_000L).describe())
        assertEquals("每天 09:05", ScheduleSpec.Daily(9, 5).describe())
    }

    // ---------- 持久化编解码（真机暴露的 bug：Entity 缺列导致时间语义全失效） ----------

    @Test
    fun `编码解码往返一致`() {
        val cases = listOf(
            ScheduleSpec.After(1_800_000L, 1_000L),
            ScheduleSpec.Every(300_000L),
            ScheduleSpec.Daily(9, 30),
        )
        for (spec in cases) {
            val round = ScheduleSpec.decode(ScheduleSpec.encode(spec))
            assertEquals("往返应保持一致: $spec", spec, round)
        }
    }

    @Test
    fun `null 与空串都解码为无时间限制`() {
        assertEquals(null, ScheduleSpec.decode(null))
        assertEquals(null, ScheduleSpec.decode(""))
        assertEquals(null, ScheduleSpec.decode("   "))
    }

    @Test
    fun `未知格式安全降级为 null`() {
        // 旧数据、未来版本、手改脏数据都不能让读取崩掉
        listOf("weekly:1", "after:abc", "every:", "daily:9", "garbage").forEach {
            assertEquals("非法输入 $it 应返回 null", null, ScheduleSpec.decode(it))
        }
    }

    @Test
    fun `编码结果人眼可读`() {
        assertEquals("every:300000", ScheduleSpec.encode(ScheduleSpec.Every(300_000L)))
        assertEquals("daily:9:5", ScheduleSpec.encode(ScheduleSpec.Daily(9, 5)))
        assertEquals("", ScheduleSpec.encode(null))
    }
}
