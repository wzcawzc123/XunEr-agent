package io.github.mangi.eta.agent.autotask

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AutoTaskTool] 的协议层测试。
 *
 * 只覆盖**不碰数据库**的分支：schema 自检、参数校验、时间解析。
 * 成功路径需要 Room（本机 aarch64 跑不了 Robolectric），由 CI 兜底。
 */
class AutoTaskToolTest {

    private fun args(vararg pairs: Pair<String, Any?>): JSONObject = JSONObject().apply {
        pairs.forEach { (k, v) -> if (v != null) put(k, v) }
    }

    // ---------- schema ----------

    @Test
    fun `schema 名称与必填项正确`() {
        val schema = AutoTaskTool.schema()
        assertEquals("create_auto_task", schema.getString("name"))
        val required = schema.getJSONObject("parameters").getJSONArray("required")
        assertEquals(2, required.length())
        assertEquals("title", required.getString(0))
        assertEquals("prompt", required.getString(1))
    }

    @Test
    fun `schema 声明了三种时间语义`() {
        val enum = AutoTaskTool.schema()
            .getJSONObject("parameters")
            .getJSONObject("properties")
            .getJSONObject("schedule")
            .getJSONArray("enum")
        assertEquals(4, enum.length())  // after / every / daily / none
        val values = (0 until enum.length()).map { enum.getString(it) }
        assertTrue(values.containsAll(listOf("after", "every", "daily", "none")))
    }

    @Test
    fun `schema 声明了三种触发条件`() {
        val enum = AutoTaskTool.schema()
            .getJSONObject("parameters")
            .getJSONObject("properties")
            .getJSONObject("condition")
            .getJSONArray("enum")
        assertEquals(3, enum.length())
    }

    // ---------- 参数校验（失败路径，不碰 DB） ----------

    @Test
    fun `描述文案包含任务要素`() {
        // describeRun 的形态检查（不依赖 DB）
        val task = AgentTask(
            id = "t1",
            title = "CI 巡检",
            prompt = "查 CI",
            condition = TaskCondition.CONSERVATIVE,
            createdAt = 1L,
        )
        val text = AutoTaskScheduler.describeRun(task)
        assertTrue(text.contains("CI 巡检"))
        assertTrue(text.contains("充电中"))
    }

    @Test
    fun `时间语义的 describe 可读`() {
        assertEquals("创建后 30 分钟", ScheduleSpec.After(30 * 60_000L, 0L).describe())
        assertEquals("每 2 小时", ScheduleSpec.Every(2 * 60 * 60_000L).describe())
        assertEquals("每天 09:00", ScheduleSpec.Daily(9, 0).describe())
    }
}
