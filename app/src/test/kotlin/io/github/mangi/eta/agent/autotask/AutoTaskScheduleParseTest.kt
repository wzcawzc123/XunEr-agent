package io.github.mangi.eta.agent.autotask

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 时间参数解析的三态测试。
 *
 * **这个文件锁死一个真 bug**：`parseSchedule` 原本用 `null` 同时表示
 * 「没有时间限制」和「参数写错」，而调用方把 null 一律当错误 ——
 * 于是 `schedule=none`（最常见的「只要条件满足就跑」）创建任务时直接失败。
 */
class AutoTaskScheduleParseTest {

    private val now = 1_700_000_000_000L

    private fun args(vararg pairs: Pair<String, Any>): JSONObject = JSONObject().apply {
        pairs.forEach { (k, v) -> put(k, v) }
    }

    @Test
    fun `缺省 schedule 视为无时间限制`() {
        val r = AutoTaskTool.parseSchedule(args(), now)
        assertTrue("未传 schedule 应视为 None，而不是错误", r is AutoTaskTool.ScheduleParse.None)
    }

    @Test
    fun `schedule=none 视为无时间限制`() {
        val r = AutoTaskTool.parseSchedule(args("schedule" to "none"), now)
        assertTrue("none 是合法取值，不能当错误", r is AutoTaskTool.ScheduleParse.None)
    }

    @Test
    fun `after 缺 after_minutes 时报错`() {
        val r = AutoTaskTool.parseSchedule(args("schedule" to "after"), now)
        assertTrue(r is AutoTaskTool.ScheduleParse.Invalid)
    }

    @Test
    fun `after 正常解析并从 now 起算`() {
        val r = AutoTaskTool.parseSchedule(
            args("schedule" to "after", "after_minutes" to 30),
            now,
        )
        assertTrue(r is AutoTaskTool.ScheduleParse.Spec)
        val spec = (r as AutoTaskTool.ScheduleParse.Spec).spec
        assertTrue(spec is ScheduleSpec.After)
        assertEquals(now + 30 * 60_000L, (spec as ScheduleSpec.After).nextRunAtMillis(now, null))
    }

    @Test
    fun `every 正常解析`() {
        val r = AutoTaskTool.parseSchedule(
            args("schedule" to "every", "every_minutes" to 60),
            now,
        )
        val spec = (r as AutoTaskTool.ScheduleParse.Spec).spec
        assertTrue(spec is ScheduleSpec.Every)
        assertEquals(3_600_000L, (spec as ScheduleSpec.Every).intervalMillis)
    }

    @Test
    fun `daily 正常解析`() {
        val r = AutoTaskTool.parseSchedule(
            args("schedule" to "daily", "at_time" to "09:30"),
            now,
        )
        val spec = (r as AutoTaskTool.ScheduleParse.Spec).spec
        assertTrue(spec is ScheduleSpec.Daily)
        assertEquals(9, (spec as ScheduleSpec.Daily).hourOfDay)
        assertEquals(30, spec.minuteOfHour)
    }

    @Test
    fun `daily 时间格式错误时报错而不是静默失败`() {
        listOf("9", "25:00", "09:70", "abc", "").forEach { bad ->
            val r = AutoTaskTool.parseSchedule(args("schedule" to "daily", "at_time" to bad), now)
            assertTrue("at_time=$bad 应判为非法", r is AutoTaskTool.ScheduleParse.Invalid)
        }
    }

    @Test
    fun `未知 schedule 取值时报错`() {
        val r = AutoTaskTool.parseSchedule(args("schedule" to "weekly"), now)
        assertTrue(r is AutoTaskTool.ScheduleParse.Invalid)
    }
}
