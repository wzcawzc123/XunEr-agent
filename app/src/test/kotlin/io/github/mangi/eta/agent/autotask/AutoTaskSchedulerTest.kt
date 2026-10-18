package io.github.mangi.eta.agent.autotask

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AutoTaskScheduler] 的决策测试。
 *
 * 重点不是"能跑"，而是**不跑时的解释是否准确** ——
 * 自动任务最伤人的地方是"什么都没发生，也不知道为什么"。
 */
class AutoTaskSchedulerTest {

    private val ready = DeviceState(charging = true, onWifi = true, screenOn = false)
    private val notReady = DeviceState(charging = false, onWifi = true, screenOn = false)

    private fun task(
        id: String = "t1",
        enabled: Boolean = true,
        createdAt: Long = 1L,
        lastRunAt: Long? = null,
    ) = AgentTask(
        id = id,
        title = "CI 巡检",
        prompt = "查 CI",
        enabled = enabled,
        lastRunAt = lastRunAt,
        createdAt = createdAt,
    )

    // ---------- 决策 ----------

    @Test
    fun `条件满足时选中任务`() {
        val d = AutoTaskScheduler.decide(listOf(task()), ready, 100L)
        assertTrue(d is AutoTaskScheduler.Decision.Run)
        assertEquals("t1", (d as AutoTaskScheduler.Decision.Run).task.id)
    }

    @Test
    fun `条件不满足时什么都不做`() {
        assertEquals(AutoTaskScheduler.Decision.Idle, AutoTaskScheduler.decide(listOf(task()), notReady, 100L))
    }

    @Test
    fun `空列表返回 Idle`() {
        assertEquals(AutoTaskScheduler.Decision.Idle, AutoTaskScheduler.decide(emptyList(), ready, 100L))
    }

    @Test
    fun `已跑过的不再执行`() {
        assertEquals(
            AutoTaskScheduler.Decision.Idle,
            AutoTaskScheduler.decide(listOf(task(lastRunAt = 50L)), ready, 100L),
        )
    }

    @Test
    fun `未启用的不执行`() {
        assertEquals(
            AutoTaskScheduler.Decision.Idle,
            AutoTaskScheduler.decide(listOf(task(enabled = false)), ready, 100L),
        )
    }

    @Test
    fun `多个可执行时选最早的`() {
        val tasks = listOf(task(id = "later", createdAt = 9L), task(id = "earlier", createdAt = 2L))
        val d = AutoTaskScheduler.decide(tasks, ready, 100L) as AutoTaskScheduler.Decision.Run
        assertEquals("earlier", d.task.id)
    }

    // ---------- 解释（"为什么没跑"） ----------

    @Test
    fun `没有任务时的解释`() {
        assertEquals("还没有自动任务", AutoTaskScheduler.explainIdle(emptyList(), ready))
    }

    @Test
    fun `全部未启用时的解释`() {
        val msg = AutoTaskScheduler.explainIdle(listOf(task(enabled = false)), ready)
        assertTrue(msg.contains("未启用"))
    }

    @Test
    fun `全部跑过时的解释`() {
        val msg = AutoTaskScheduler.explainIdle(listOf(task(lastRunAt = 1L)), ready)
        assertTrue(msg.contains("已执行过"))
    }

    @Test
    fun `条件未满足时说明缺什么`() {
        val msg = AutoTaskScheduler.explainIdle(listOf(task()), notReady)
        assertTrue("应说明等待的条件，实际：$msg", msg.contains("等待条件"))
        assertTrue(msg.contains("充电中"))
    }

    @Test
    fun `条件已满足但还没被唤醒时给出正确解释`() {
        val msg = AutoTaskScheduler.explainIdle(listOf(task()), ready)
        assertTrue(msg.contains("等下一次唤醒"))
    }

    // ---------- 执行说明 ----------

    @Test
    fun `执行说明包含任务名与条件`() {
        val text = AutoTaskScheduler.describeRun(task())
        assertTrue(text.contains("CI 巡检"))
        assertTrue(text.contains("充电中"))
    }
}
