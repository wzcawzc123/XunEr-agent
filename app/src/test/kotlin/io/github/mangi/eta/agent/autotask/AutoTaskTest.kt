package io.github.mangi.eta.agent.autotask

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自动任务第一层（条件 / 模型 / 队列）的纯逻辑测试。
 *
 * 这一层是整套自动任务的安全底座：条件判错会让任务在错误时机跑，
 * 队列选错会在无人看管时多花钱。所以每个分支都钉死。
 */
class AutoTaskTest {

    private val chargingIdle = DeviceState(charging = true, onWifi = true, screenOn = false)
    private val unplugged = DeviceState(charging = false, onWifi = true, screenOn = false)
    private val noWifi = DeviceState(charging = true, onWifi = false, screenOn = false)
    private val screenOn = DeviceState(charging = true, onWifi = true, screenOn = true)

    private fun task(
        id: String = "t1",
        enabled: Boolean = true,
        createdAt: Long = 1L,
        lastRunAt: Long? = null,
        condition: TaskCondition = TaskCondition.CONSERVATIVE,
    ) = AgentTask(
        id = id,
        title = "CI 巡检",
        prompt = "查最新一次 CI",
        condition = condition,
        enabled = enabled,
        lastRunAt = lastRunAt,
        createdAt = createdAt,
    )

    // ---------- 条件 ----------

    @Test
    fun `保守条件只在充电加WiFi加灭屏时满足`() {
        val c = TaskCondition.CONSERVATIVE
        assertTrue(c.satisfiedBy(chargingIdle))
        assertFalse(c.satisfiedBy(unplugged))
        assertFalse(c.satisfiedBy(noWifi))
        assertFalse(c.satisfiedBy(screenOn))
    }

    @Test
    fun `只要WiFi的条件忽略充电与亮屏`() {
        val c = TaskCondition.WIFI_ONLY
        assertTrue(c.satisfiedBy(unplugged))
        assertTrue(c.satisfiedBy(screenOn))
        assertFalse("没 WiFi 仍不满足", c.satisfiedBy(noWifi))
    }

    @Test
    fun `ANYTIME 任何状态都满足`() {
        val c = TaskCondition.ANYTIME
        listOf(chargingIdle, unplugged, noWifi, screenOn).forEach {
            assertTrue(c.satisfiedBy(it))
        }
    }

    @Test
    fun `条件描述可读`() {
        assertEquals("充电中 + 连 WiFi + 屏幕熄灭", TaskCondition.CONSERVATIVE.describe())
        assertEquals("随时", TaskCondition.ANYTIME.describe())
    }

    // ---------- 任务校验 ----------

    @Test
    fun `默认不启用`() {
        val t = AgentTask.create("t", "标题", "指令", nowMillis = 1L).getOrThrow()
        assertFalse("新建任务必须默认关闭", t.enabled)
        assertFalse(t.canRun(chargingIdle, 1_000_000L))
    }

    @Test
    fun `空标题或空指令被拒`() {
        assertTrue(AgentTask.create("t", "  ", "指令", nowMillis = 1L).isFailure)
        assertTrue(AgentTask.create("t", "标题", "  ", nowMillis = 1L).isFailure)
    }

    @Test
    fun `轮数上限必须在允许区间`() {
        assertTrue(AgentTask.create("t", "a", "b", maxRounds = 0, nowMillis = 1L).isFailure)
        assertTrue(
            AgentTask.create(
                "t", "a", "b",
                maxRounds = AgentTask.MAX_ALLOWED_ROUNDS + 1,
                nowMillis = 1L,
            ).isFailure,
        )
        assertTrue(AgentTask.create("t", "a", "b", maxRounds = 5, nowMillis = 1L).isSuccess)
    }

    @Test
    fun `第一版任务恒为只读`() {
        val t = AgentTask.create("t", "标题", "指令", nowMillis = 1L).getOrThrow()
        assertTrue("护栏：第一版只允许只读", t.readOnly)
    }

    // ---------- 队列 ----------

    @Test
    fun `未启用的任务不会被选中`() {
        assertNull(TaskQueueSelector.pick(listOf(task(enabled = false)), chargingIdle, 1_000_000L))
    }

    @Test
    fun `条件不满足时不会被选中`() {
        assertNull(TaskQueueSelector.pick(listOf(task()), unplugged, 1_000_000L))
    }

    @Test
    fun `已执行过的任务不再重复`() {
        assertNull(TaskQueueSelector.pick(listOf(task(lastRunAt = 100L)), chargingIdle, 1_000_000L))
    }

    @Test
    fun `多个可执行任务时取创建最早的一个`() {
        val early = task(id = "early", createdAt = 10L)
        val late = task(id = "late", createdAt = 20L)
        assertEquals("early", TaskQueueSelector.pick(listOf(late, early), chargingIdle, 1_000_000L)?.id)
    }

    @Test
    fun `一次只挑一个任务`() {
        val tasks = listOf(task(id = "a", createdAt = 1L), task(id = "b", createdAt = 2L))
        // pick 返回单个对象，天然保证一次只做一件事
        assertEquals("a", TaskQueueSelector.pick(tasks, chargingIdle, 1_000_000L)?.id)
    }

    @Test
    fun `队列概览分类正确`() {
        val tasks = listOf(
            task(id = "waiting", condition = TaskCondition.CONSERVATIVE), // 当前不满足？用屏幕亮的 state 测
            task(id = "ready"),
            task(id = "done", lastRunAt = 5L),
            task(id = "disabled", enabled = false),
        )
        val o = TaskQueueSelector.overview(tasks, chargingIdle)
        assertEquals(0, o.waiting)
        assertEquals(2, o.ready)      // waiting 那条在 chargingIdle 下其实也满足
        assertEquals(1, o.done)
        assertEquals(1, o.disabled)
        assertEquals(4, o.total)
    }

    @Test
    fun `队列概览在条件不满足时计入 waiting`() {
        val tasks = listOf(task(id = "a"), task(id = "b"))
        val o = TaskQueueSelector.overview(tasks, screenOn)
        assertEquals(2, o.waiting)
        assertEquals(0, o.ready)
    }

    // ---------- 位掩码往返 ----------

    @Test
    fun `条件位掩码往返无损`() {
        val cases = listOf(
            TaskCondition.CONSERVATIVE,
            TaskCondition.WIFI_ONLY,
            TaskCondition.ANYTIME,
            TaskCondition(requireCharging = true, requireWifi = false, requireScreenOff = true),
        )
        cases.forEach { original ->
            val roundTrip = TaskCondition.fromMask(original.toMask())
            assertEquals("往返应完全一致: $original", original, roundTrip)
        }
    }

    @Test
    fun `未知位被忽略而不是报错`() {
        // 假设未来加了「蓝牙」= 8 位，旧版本读到应忽略该位
        val withUnknown = TaskCondition.CONSERVATIVE.toMask() or 8
        assertEquals(TaskCondition.CONSERVATIVE, TaskCondition.fromMask(withUnknown))
    }

    // ---------- 重复任务语义（审查发现的缺陷，回归测试） ----------

    @Test
    fun `一次性任务跑过就不再选`() {
        val once = task(id = "once", lastRunAt = 500L).copy(
            schedule = ScheduleSpec.After(60_000L, anchor = 0L),
        )
        assertNull(
            "after 类型投递后不应再跑",
            TaskQueueSelector.pick(listOf(once), chargingIdle, 100_000_000L),
        )
    }

    @Test
    fun `every 任务到点后可以再跑`() {
        val every = task(id = "every", lastRunAt = 1_000_000L).copy(
            schedule = ScheduleSpec.Every(60_000L),
        )
        // lastRunAt + 间隔 = 1_060_000；now 超过它 → 应被选中
        assertEquals(
            "every 类型到点后必须能再次投递",
            "every",
            TaskQueueSelector.pick(listOf(every), chargingIdle, 1_100_000L)?.id,
        )
    }

    @Test
    fun `every 任务未到点不跑`() {
        val every = task(id = "every", lastRunAt = 1_000_000L).copy(
            schedule = ScheduleSpec.Every(600_000L),  // 10 分钟
        )
        assertNull(
            "距离上次不足间隔时不该跑",
            TaskQueueSelector.pick(listOf(every), chargingIdle, 1_100_000L),
        )
    }

    @Test
    fun `daily 任务跨天后可以再跑`() {
        val daily = task(id = "daily", lastRunAt = 1_000_000L).copy(
            schedule = ScheduleSpec.Daily(9, 0),
        )
        // 用一个远期时刻，保证已过下一个 9 点
        val far = System.currentTimeMillis() + 3L * 24 * 3600 * 1000
        assertEquals(
            "daily 类型跨天后必须能再次投递",
            "daily",
            TaskQueueSelector.pick(listOf(daily), chargingIdle, far)?.id,
        )
    }

}
