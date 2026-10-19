package io.github.mangi.eta.agent.plan

import io.github.mangi.eta.data.db.plan.ConversationTodoDao
import io.github.mangi.eta.data.db.plan.ConversationTodoEntity
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun `非法状态被拒绝且整批不落库`() {
        val dao = FakeTodoDao()
        val store = AgentPlanStore(dao)
        AgentPlanTool.execute(args("甲" to null, "乙" to null), store, "conv-1", 1L)

        val result = AgentPlanTool.execute(args("甲" to "completed", "乙" to "done"), store, "conv-1", 2L)
        assertTrue("实际回执：$result", result.startsWith("失败"))
        assertTrue("错误里要列出可用值", result.contains("pending"))
        // 校验失败不得覆盖已有清单
        assertEquals(listOf("甲", "乙"), store.loadBlocking("conv-1").map { it.subject })
    }

    @Test
    fun `取消项在回执与概览中单独计数`() {
        val dao = FakeTodoDao()
        val store = AgentPlanStore(dao)
        val result = AgentPlanTool.execute(
            args("完成项" to "completed", "放弃项" to "cancelled", "待办项" to null),
            store,
            "conv-1",
            1L,
        )
        assertTrue("实际回执：$result", result.contains("1/2 完成"))
        assertTrue("取消必须单独可见：$result", result.contains("1 项已取消"))

        val overview = AgentPlanTool.overviewText(store.loadBlocking("conv-1"))!!
        assertTrue("概览也要带取消数：$overview", overview.contains("1 已取消"))
    }

    @Test
    fun `删掉已完成项时回执给出删除计数`() {
        val store = AgentPlanStore(FakeTodoDao())
        AgentPlanTool.execute(args("甲" to "completed", "乙" to null, "丙" to null), store, "c", 1L)
        val result = AgentPlanTool.execute(args("甲" to "completed", "乙" to null), store, "c", 2L)
        assertTrue("全量覆盖必须告诉模型删了什么：$result", result.contains("删除 1 条"))
    }

    @Test
    fun `已完成回退被拒除非显式 reopen`() {
        val store = AgentPlanStore(FakeTodoDao())
        fun payload(status: String, reopen: Boolean? = null) = JSONObject().put(
            "items",
            JSONArray().put(
                JSONObject().put("id", "a").put("subject", "甲").put("status", status).apply {
                    if (reopen != null) put("reopen", reopen)
                },
            ),
        )

        AgentPlanTool.execute(payload("completed"), store, "c", 1L)
        val regressed = AgentPlanTool.execute(payload("pending"), store, "c", 2L)
        assertTrue("实际回执：$regressed", regressed.startsWith("失败"))
        assertTrue(regressed.contains("reopen"))

        val reopened = AgentPlanTool.execute(payload("pending", reopen = true), store, "c", 3L)
        assertTrue("实际回执：$reopened", reopened.contains("任务清单已更新"))
    }

    @Test
    fun `完整清单包含全部步骤且可被注入`() {
        val store = AgentPlanStore(FakeTodoDao())
        AgentPlanTool.execute(args("收集资料" to "completed", "写初稿" to "in_progress", "校对" to null), store, "c", 1L)
        val text = AgentPlanTool.checklistText(store.loadBlocking("c"))!!
        assertTrue(text.contains("当前任务清单"))
        assertTrue(text.contains("1/3 完成"))
        assertTrue(text.contains("收集资料"))
        assertTrue(text.contains("写初稿"))
        assertTrue(text.contains("校对"))
        assertTrue(text.contains("进行中"))
    }

    @Test
    fun `完整清单超预算时明确标注省略数量`() {
        val store = AgentPlanStore(FakeTodoDao())
        AgentPlanTool.execute(
            args("步骤一" to null, "步骤二" to null, "步骤三" to null, "步骤四" to null),
            store,
            "c",
            1L,
        )
        val text = AgentPlanTool.checklistText(store.loadBlocking("c"), maxChars = 80)!!
        assertTrue("超预算要说明省略了多少项：$text", text.contains("未显示"))
    }

    // ---- 执行期范围漂移 ----

    @Test
    fun `执行期只推进状态不算超出批准范围`() {
        val store = AgentPlanStore(FakeTodoDao())
        AgentPlanTool.execute(args("做题" to null, "交付" to null, "复盘" to null), store, "c", 1L)
        val result = AgentPlanTool.execute(
            args("做题" to "completed", "交付" to "in_progress", "复盘" to null),
            store,
            "c",
            2L,
            enforceApprovedScope = true,
        )
        assertFalse("只改状态不该触发重新批准：$result", result.startsWith(AgentPlanTool.SCOPE_CHANGE_PREFIX))
    }

    @Test
    fun `执行期新增步骤会标记超出批准范围且清单照常落库`() {
        val store = AgentPlanStore(FakeTodoDao())
        AgentPlanTool.execute(args("做题" to "in_progress", "交付" to null, "复盘" to null), store, "c", 1L)
        val result = AgentPlanTool.execute(
            args("做题" to "in_progress", "交付" to null, "复盘" to null, "顺手重构" to null),
            store,
            "c",
            2L,
            enforceApprovedScope = true,
        )
        assertTrue("实际回执：$result", result.startsWith(AgentPlanTool.SCOPE_CHANGE_PREFIX))
        assertTrue(result.contains("新增 1 条"))
        assertEquals(4, store.loadBlocking("c").size)
    }

    @Test
    fun `执行期删掉中间步骤只报删除不误报改写`() {
        val store = AgentPlanStore(FakeTodoDao())
        AgentPlanTool.execute(args("一" to null, "二" to null, "三" to null, "四" to null), store, "c", 1L)
        // 删掉"二"后，"三""四"的缺省 id 会从 step-3/step-4 平移到 step-2/step-3。
        // 按 id 比较会把这次删除误报成"重写了两条"，所以这里锁住按 subject 比较的行为。
        val result = AgentPlanTool.execute(
            args("一" to null, "三" to null, "四" to null),
            store,
            "c",
            2L,
            enforceApprovedScope = true,
        )
        assertTrue("实际回执：$result", result.startsWith(AgentPlanTool.SCOPE_CHANGE_PREFIX))
        assertTrue(result.contains("删除 1 条"))
        assertFalse("不该把 id 平移误报成新增：$result", result.contains("新增"))
    }

    @Test
    fun `规划阶段改结构不会标记范围变更`() {
        val store = AgentPlanStore(FakeTodoDao())
        AgentPlanTool.execute(args("一" to null, "二" to null, "三" to null), store, "c", 1L)
        val result = AgentPlanTool.execute(
            args("一" to null, "二" to null, "三" to null, "四" to null),
            store,
            "c",
            2L,
            enforceApprovedScope = false,
        )
        assertFalse(result.startsWith(AgentPlanTool.SCOPE_CHANGE_PREFIX))
    }

    @Test
    fun `空清单对比不视为范围变更`() {
        assertNull(AgentPlanTool.structuralChangeText(emptyList(), emptyList()))
        val items = listOf(AgentPlanItem.create(0, null, "一", null, null).getOrThrow())
        assertNull(AgentPlanTool.structuralChangeText(emptyList(), items))
    }

    // ---- 计划正文注入 ----

    @Test
    fun `计划正文与清单一起注入`() {
        val store = AgentPlanStore(FakeTodoDao())
        AgentPlanTool.execute(args("调研" to "in_progress", "改代码" to null, "验证" to null), store, "c", 1L)
        val text = AgentPlanTool.planContextText(
            markdown = "# 目标\n把通知权限开到可用\n\n# 验收\n能收到测试通知",
            items = store.loadBlocking("c"),
        )!!
        assertTrue(text.contains("已批准计划"))
        assertTrue(text.contains("把通知权限开到可用"))
        assertTrue(text.contains("能收到测试通知"))
        assertTrue(text.contains("当前任务清单"))
        assertTrue(text.contains("调研"))
    }

    @Test
    fun `没有正文时退化为纯清单`() {
        val store = AgentPlanStore(FakeTodoDao())
        AgentPlanTool.execute(args("一" to null, "二" to null, "三" to null), store, "c", 1L)
        val items = store.loadBlocking("c")
        assertEquals(AgentPlanTool.checklistText(items), AgentPlanTool.planContextText(null, items))
        assertEquals(AgentPlanTool.checklistText(items), AgentPlanTool.planContextText("   ", items))
    }

    @Test
    fun `正文超预算时截断并标注`() {
        val store = AgentPlanStore(FakeTodoDao())
        AgentPlanTool.execute(args("一" to null, "二" to null, "三" to null), store, "c", 1L)
        val text = AgentPlanTool.planContextText(
            markdown = "目标".repeat(200),
            items = store.loadBlocking("c"),
            maxPlanChars = 50,
        )!!
        assertTrue("超预算要明确说明截断：$text", text.contains("已截断"))
        assertTrue(text.contains("当前任务清单"))
    }
}
