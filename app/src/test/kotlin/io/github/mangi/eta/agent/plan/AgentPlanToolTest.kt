package io.github.mangi.eta.agent.plan

import io.github.mangi.eta.data.db.plan.ConversationTodoDao
import io.github.mangi.eta.data.db.plan.ConversationTodoEntity
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `todo_write` 的协议层测试。
 *
 * 用内存版 [FakeTodoDao] 替代 Room —— 这一层要验证的是「JSON 进、文本出」的行为，
 * 不涉及 SQL；用 fake 可以让测试跑在纯 JVM 上（本机 aarch64 可用）。
 */
class AgentPlanToolTest {

    private class FakeTodoDao : ConversationTodoDao {
        val store = mutableMapOf<String, List<ConversationTodoEntity>>()

        override suspend fun list(conversationId: String) = store[conversationId].orEmpty()
        override fun listBlocking(conversationId: String) = store[conversationId].orEmpty()
        override suspend fun upsert(items: List<ConversationTodoEntity>) = Unit
        override suspend fun deleteAll(conversationId: String) = Unit
        override fun deleteAllBlocking(conversationId: String) = Unit
        override fun upsertBlocking(items: List<ConversationTodoEntity>) = Unit
        override suspend fun replaceAll(conversationId: String, items: List<ConversationTodoEntity>) {
            store[conversationId] = items
        }

        override fun replaceAllBlocking(conversationId: String, items: List<ConversationTodoEntity>) {
            store[conversationId] = items
        }
    }

    private fun args(vararg payload: Pair<String, Any?>): JSONObject {
        val array = JSONArray()
        payload.forEach { (subject, status) ->
            array.put(
                JSONObject().put("subject", subject).apply {
                    if (status != null) put("status", status)
                },
            )
        }
        return JSONObject().put("items", array)
    }

    @Test
    fun `写入成功时回执包含进度与当前步骤`() {
        val dao = FakeTodoDao()
        val store = AgentPlanStore(dao)
        val result = AgentPlanTool.execute(
            args("收集资料" to "completed", "写初稿" to "in_progress", "校对" to null),
            store,
            "conv-1",
            1_000L,
        )
        assertTrue("实际回执：$result", result.contains("1/3 完成"))
        assertTrue(result.contains("写初稿"))
        assertTrue(result.contains("校对") || result.contains("下一条"))
    }

    @Test
    fun `写入后能读回且顺序保持`() {
        val dao = FakeTodoDao()
        val store = AgentPlanStore(dao)
        AgentPlanTool.execute(args("第一步" to null, "第二步" to null, "第三步" to null), store, "conv-1", 1L)
        val loaded = store.loadBlocking("conv-1")
        assertEquals(listOf("第一步", "第二步", "第三步"), loaded.map { it.subject })
    }

    @Test
    fun `空数组等价于清空`() {
        val dao = FakeTodoDao()
        val store = AgentPlanStore(dao)
        AgentPlanTool.execute(args("甲" to null, "乙" to null, "丙" to null), store, "conv-1", 1L)
        assertEquals(3, store.loadBlocking("conv-1").size)

        val result = AgentPlanTool.execute(JSONObject().put("items", JSONArray()), store, "conv-1", 2L)
        assertTrue("实际回执：$result", result.contains("已清空"))
        assertTrue(store.loadBlocking("conv-1").isEmpty())
    }

    @Test
    fun `缺少 items 参数给出清晰报错`() {
        val result = AgentPlanTool.execute(JSONObject(), AgentPlanStore(FakeTodoDao()), "conv-1", 1L)
        assertTrue(result.startsWith("失败"))
        assertTrue(result.contains("items"))
    }

    @Test
    fun `缺少 subject 的条目被拒且整批不落库`() {
        val dao = FakeTodoDao()
        val store = AgentPlanStore(dao)
        val bad = JSONObject().put("items", JSONArray().put(JSONObject().put("status", "pending")))
        val result = AgentPlanTool.execute(bad, store, "conv-1", 1L)
        assertTrue(result.startsWith("失败"))
        assertTrue("校验失败时不应写入任何内容", store.loadBlocking("conv-1").isEmpty())
    }

    @Test
    fun `多条进行中被拒`() {
        val dao = FakeTodoDao()
        val store = AgentPlanStore(dao)
        val result = AgentPlanTool.execute(
            args("甲" to "in_progress", "乙" to "in_progress"),
            store,
            "conv-1",
            1L,
        )
        assertTrue(result.startsWith("失败"))
        assertTrue(result.contains("in_progress"))
    }

    @Test
    fun `会话 id 为空时拒绝写入`() {
        val result = AgentPlanTool.execute(args("甲" to null), AgentPlanStore(FakeTodoDao()), "", 1L)
        assertTrue(result.contains("无法保存"))
    }

    @Test
    fun `不同会话的清单互相隔离`() {
        val dao = FakeTodoDao()
        val store = AgentPlanStore(dao)
        AgentPlanTool.execute(args("A 的步骤" to null), store, "conv-A", 1L)
        AgentPlanTool.execute(args("B 的步骤" to null), store, "conv-B", 1L)
        assertEquals(listOf("A 的步骤"), store.loadBlocking("conv-A").map { it.subject })
        assertEquals(listOf("B 的步骤"), store.loadBlocking("conv-B").map { it.subject })
    }

    // ---------- overviewText ----------

    @Test
    fun `空清单不产生概览`() {
        assertNull(AgentPlanTool.overviewText(emptyList()))
    }

    @Test
    fun `概览包含进度与进行中项`() {
        val items = listOf(
            AgentPlanItem.create(0, "a", "甲", null, AgentPlanStatus.COMPLETED).getOrThrow(),
            AgentPlanItem.create(1, "b", "乙", null, AgentPlanStatus.IN_PROGRESS).getOrThrow(),
        )
        val text = AgentPlanTool.overviewText(items)
        assertNotNull(text)
        assertTrue(text!!.contains("1/2 完成"))
        assertTrue(text.contains("进行中：乙"))
    }

    @Test
    fun `没有进行中时概览提示下一条`() {
        val items = listOf(
            AgentPlanItem.create(0, "a", "甲", null, AgentPlanStatus.COMPLETED).getOrThrow(),
            AgentPlanItem.create(1, "b", "乙", null, AgentPlanStatus.PENDING).getOrThrow(),
        )
        val text = AgentPlanTool.overviewText(items)!!
        assertTrue(text.contains("下一条：乙"))
    }

    @Test
    fun `schema 声明了必需字段与状态枚举`() {
        val schema = AgentPlanTool.schema()
        assertEquals(AgentPlanTool.NAME, schema.getString("name"))
        val itemsSchema = schema.getJSONObject("parameters")
            .getJSONObject("properties")
            .getJSONObject("items")
        val statusEnum = itemsSchema.getJSONObject("items")
            .getJSONObject("properties")
            .getJSONObject("status")
            .getJSONArray("enum")
        assertEquals(4, statusEnum.length())
    }
}
