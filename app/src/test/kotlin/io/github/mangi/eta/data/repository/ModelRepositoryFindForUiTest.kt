package io.github.mangi.eta.data.repository

import io.github.mangi.eta.data.model.Model
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 视觉开关回归（2026-10-28 真机 bug）。
 *
 * UI 的 AgentModelOptionUi.modelId 携带 API 模型名（model.modelId），而 setModelVision
 * 原实现按行 id（model.id）匹配 → 永久不命中 → 开关点了没反应。findForUi 必须两种都认。
 */
class ModelRepositoryFindForUiTest {

    private val byApiName = Model(
        id = "3aed1191-88c5-4dc2-962f-171d91db426f",
        modelId = "sensenova-6.8-flash-lite",
        displayName = "sensenova-6.8-flash-lite",
    )
    private val another = Model(
        id = "builtin-some-model",
        modelId = "claude-fable-5",
        displayName = "Claude Fable 5",
    )
    private val models = listOf(byApiName, another)

    @Test
    fun `按 UI 传入的 API 模型名可命中`() {
        assertEquals(byApiName, ModelRepository.findForUi(models, "sensenova-6.8-flash-lite"))
    }

    @Test
    fun `按行 id 也可命中`() {
        assertEquals(byApiName, ModelRepository.findForUi(models, "3aed1191-88c5-4dc2-962f-171d91db426f"))
    }

    @Test
    fun `空与未知标识返回 null`() {
        assertNull(ModelRepository.findForUi(models, ""))
        assertNull(ModelRepository.findForUi(models, "not-a-model"))
        assertNull(ModelRepository.findForUi(emptyList(), "claude-fable-5"))
    }

    @Test
    fun `行 id 优先于 API 名`() {
        // 构造一个"API 名恰好是另一个模型行 id"的极端情况：行 id 命中优先（id 是权威标识）
        val shadow = Model(id = "claude-fable-5", modelId = "x", displayName = "Shadow")
        val list = listOf(another, shadow)
        assertEquals(shadow, ModelRepository.findForUi(list, "claude-fable-5"))
    }
}
