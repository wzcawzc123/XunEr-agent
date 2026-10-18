package io.github.mangi.eta.agent.plan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AgentPlanItem] 与 [AgentPlanStatus] 的纯逻辑测试。
 *
 * 这一层刻意不依赖 Android / Robolectric：校验规则是整套功能最容易出错的地方
 * （模型送来的参数永远比预期脏），所以用最快的 JVM 测试把每个分支压死。
 */
class AgentPlanItemTest {

    // ---------- AgentPlanStatus ----------

    @Test
    fun `状态解析接受下划线字面量与大小写差异`() {
        assertEquals(AgentPlanStatus.IN_PROGRESS, AgentPlanStatus.fromWire("in_progress"))
        assertEquals(AgentPlanStatus.IN_PROGRESS, AgentPlanStatus.fromWire("  IN_PROGRESS "))
        assertEquals(AgentPlanStatus.COMPLETED, AgentPlanStatus.fromWire("Completed"))
    }

    @Test
    fun `状态解析拒绝别名与空值`() {
        // 不接受 done / 完成 之类的别名：让模型的错误写法暴露出来，而不是静默通过。
        assertNull(AgentPlanStatus.fromWire("done"))
        assertNull(AgentPlanStatus.fromWire("完成"))
        assertNull(AgentPlanStatus.fromWire(""))
        assertNull(AgentPlanStatus.fromWire(null))
    }

    @Test
    fun `终态判定正确`() {
        assertTrue(AgentPlanStatus.COMPLETED.isTerminal)
        assertTrue(AgentPlanStatus.CANCELLED.isTerminal)
        assertTrue(!AgentPlanStatus.PENDING.isTerminal)
        assertTrue(!AgentPlanStatus.IN_PROGRESS.isTerminal)
    }

    // ---------- create ----------

    @Test
    fun `缺少 subject 时给出可直接读懂的报错`() {
        val result = AgentPlanItem.create(0, null, "   ", null, null)
        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue("报错应指出第几条与缺什么，实际：$message", message.contains("第 1 条") && message.contains("subject"))
    }

    @Test
    fun `subject 超长被拒绝`() {
        val tooLong = "步".repeat(AgentPlanItem.MAX_TEXT_CHARS + 1)
        val result = AgentPlanItem.create(0, null, tooLong, null, null)
        assertTrue(result.isFailure)
    }

    @Test
    fun `缺省字段补齐为稳定默认值`() {
        val item = AgentPlanItem.create(2, null, "修复压缩判据", null, null).getOrThrow()
        assertEquals("step-3", item.id)
        assertEquals("修复压缩判据", item.subject)
        assertEquals("正在修复压缩判据", item.activeForm)
        assertEquals(AgentPlanStatus.PENDING, item.status)
    }

    @Test
    fun `显式提供的 activeForm 不被覆盖`() {
        val item = AgentPlanItem.create(0, "a", "跑测试", "正在跑单元测试", AgentPlanStatus.IN_PROGRESS).getOrThrow()
        assertEquals("正在跑单元测试", item.activeForm)
        assertEquals(AgentPlanStatus.IN_PROGRESS, item.status)
    }

    @Test
    fun `首尾空白被裁剪`() {
        val item = AgentPlanItem.create(0, null, "  部署  ", "  正在部署  ", null).getOrThrow()
        assertEquals("部署", item.subject)
        assertEquals("正在部署", item.activeForm)
    }

    // ---------- validateAll ----------

    @Test
    fun `空清单是合法的（等于清空）`() {
        assertTrue(AgentPlanItem.validateAll(emptyList()).isSuccess)
    }

    @Test
    fun `id 重复被拒绝`() {
        val items = listOf(
            AgentPlanItem.create(0, "same", "A", null, null).getOrThrow(),
            AgentPlanItem.create(1, "same", "B", null, null).getOrThrow(),
        )
        val result = AgentPlanItem.validateAll(items)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("重复"))
    }

    @Test
    fun `超过条数上限被拒绝`() {
        val items = (0..AgentPlanItem.MAX_ITEMS).map {
            AgentPlanItem.create(it, "id-$it", "步骤 $it", null, null).getOrThrow()
        }
        assertTrue(AgentPlanItem.validateAll(items).isFailure)
    }

    @Test
    fun `同时多条进行中被拒绝`() {
        val items = listOf(
            AgentPlanItem.create(0, "a", "A", null, AgentPlanStatus.IN_PROGRESS).getOrThrow(),
            AgentPlanItem.create(1, "b", "B", null, AgentPlanStatus.IN_PROGRESS).getOrThrow(),
        )
        val result = AgentPlanItem.validateAll(items)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("in_progress"))
    }

    @Test
    fun `恰好一条进行中是合法的`() {
        val items = listOf(
            AgentPlanItem.create(0, "a", "A", null, AgentPlanStatus.COMPLETED).getOrThrow(),
            AgentPlanItem.create(1, "b", "B", null, AgentPlanStatus.IN_PROGRESS).getOrThrow(),
            AgentPlanItem.create(2, "c", "C", null, AgentPlanStatus.PENDING).getOrThrow(),
        )
        assertTrue(AgentPlanItem.validateAll(items).isSuccess)
    }

    // ---------- progressOf ----------

    @Test
    fun `进度统计忽略已取消项`() {
        val items = listOf(
            AgentPlanItem.create(0, "a", "A", null, AgentPlanStatus.COMPLETED).getOrThrow(),
            AgentPlanItem.create(1, "b", "B", null, AgentPlanStatus.PENDING).getOrThrow(),
            AgentPlanItem.create(2, "c", "C", null, AgentPlanStatus.CANCELLED).getOrThrow(),
        )
        assertEquals(1 to 2, AgentPlanItem.progressOf(items))
    }

    @Test
    fun `空清单进度为零`() {
        assertEquals(0 to 0, AgentPlanItem.progressOf(emptyList()))
    }
}
