package io.github.mangi.eta.agent.plan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AgentPlanUi] 解析测试。
 *
 * 这个用例的职责是**锁死回执格式**：[AgentPlanUi] 依赖 [AgentPlanTool] 生成的回执文本，
 * 两者是隐式契约。一旦有人改了回执措辞，这里会立刻失败，而不是等用户发现进度条消失。
 */
class AgentPlanUiTest {

    private fun receipt() = """
        任务清单已更新：1/3 完成。当前进行中：写初稿。
        1. [已完成] 收集资料
        2. [进行中] 写初稿
        3. [待开始] 校对
    """.trimIndent()

    @Test
    fun `解析出进度与进行中项`() {
        val ui = AgentPlanUi.parseFrom(receipt())!!
        assertEquals(1, ui.done)
        assertEquals(3, ui.total)
        assertEquals("写初稿", ui.running)
        assertEquals(3, ui.items.size)
        assertEquals("已完成", ui.items[0].label)
        assertEquals("收集资料", ui.items[0].subject)
    }

    @Test
    fun `全部完成时 isActive 为 false`() {
        val text = """
            任务清单已更新：2/2 完成。没有待开始或进行中的条目。
            1. [已完成] 甲
            2. [已完成] 乙
        """.trimIndent()
        val ui = AgentPlanUi.parseFrom(text)!!
        assertEquals(2, ui.done)
        assertTrue(!ui.isActive)
        assertNull(ui.running)
    }

    @Test
    fun `清空回执解析为空清单`() {
        val ui = AgentPlanUi.parseFrom("已清空任务清单。")!!
        assertEquals(0, ui.total)
        assertTrue(ui.items.isEmpty())
    }

    @Test
    fun `取消项解析为取消状态并单独计数`() {
        val text = """
            任务清单已更新：1/1 完成，1 项已取消。所有条目均已结束，其中 1 条被取消。
            1. [已完成] 甲
            2. [已取消] 乙
        """.trimIndent()
        val ui = AgentPlanUi.parseFrom(text)!!
        assertEquals(1, ui.done)
        assertEquals(1, ui.total)
        assertEquals(1, ui.cancelled)
        assertEquals(AgentPlanStatus.CANCELLED, ui.items[1].status)
        // 全部完成但有取消项：必须继续展示，不能以 1/1 的假象消失。
        assertTrue(ui.isActive)
        assertTrue(ui.isTerminalWithCancelled)
    }

    @Test
    fun `全部取消时仍返回摘要而不是回退到旧清单`() {
        val text = """
            任务清单已更新：0/0 完成，2 项已取消。所有条目均已结束，其中 2 条被取消。
            1. [已取消] 甲
            2. [已取消] 乙
        """.trimIndent()
        val ui = AgentPlanUi.parseFrom(text)!!
        assertEquals(2, ui.cancelled)
        assertEquals(2, ui.items.size)
        assertTrue(!ui.isActive)
    }

    @Test
    fun `失败回执不被解析成清单`() {
        assertNull(AgentPlanUi.parseFrom("失败：items[1].status=\"done\" 不合法"))
    }

    @Test
    fun `无关消息返回 null`() {
        assertNull(AgentPlanUi.parseFrom("这是一条普通回复"))
        assertNull(AgentPlanUi.parseFrom(""))
    }

    @Test
    fun `与真实回执格式保持一致`() {
        // 直接走 AgentPlanTool 的回执生成路径，验证两边没有漂移。
        val items = listOf(
            AgentPlanItem.create(0, "a", "第一步", null, AgentPlanStatus.COMPLETED).getOrThrow(),
            AgentPlanItem.create(1, "b", "第二步", null, AgentPlanStatus.IN_PROGRESS).getOrThrow(),
            AgentPlanItem.create(2, "c", "第三步", null, AgentPlanStatus.PENDING).getOrThrow(),
        )
        val text = AgentPlanTool.execute(
            args = org.json.JSONObject().put(
                "items",
                org.json.JSONArray().apply {
                    items.forEach {
                        put(
                            org.json.JSONObject()
                                .put("subject", it.subject)
                                .put("status", it.status.wire),
                        )
                    }
                },
            ),
            store = AgentPlanStore(FakeDao()),
            conversationId = "c1",
            nowMillis = 1L,
        )
        val ui = AgentPlanUi.parseFrom(text)!!
        assertEquals(1, ui.done)
        assertEquals(3, ui.total)
        assertEquals("第二步", ui.running)
        assertEquals(3, ui.items.size)
    }

    private class FakeDao : io.github.mangi.eta.data.db.plan.ConversationTodoDao {
        override suspend fun list(conversationId: String) = emptyList<io.github.mangi.eta.data.db.plan.ConversationTodoEntity>()
        override fun listBlocking(conversationId: String) = emptyList<io.github.mangi.eta.data.db.plan.ConversationTodoEntity>()
        override suspend fun upsert(items: List<io.github.mangi.eta.data.db.plan.ConversationTodoEntity>) = Unit
        override suspend fun deleteAll(conversationId: String) = Unit
        override fun deleteAllBlocking(conversationId: String) = Unit
        override fun upsertBlocking(items: List<io.github.mangi.eta.data.db.plan.ConversationTodoEntity>) = Unit
        override suspend fun replaceAll(conversationId: String, items: List<io.github.mangi.eta.data.db.plan.ConversationTodoEntity>) = Unit
        override fun replaceAllBlocking(conversationId: String, items: List<io.github.mangi.eta.data.db.plan.ConversationTodoEntity>) = Unit
    }
}
