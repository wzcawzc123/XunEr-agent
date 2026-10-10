package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.model.AgentContextSnapshot

/**
 * 手动压缩结果通知文案。
 *
 * 对齐 deepseek-harness 的 shadowed 语义（"Compacted N history items (~M tokens)"）：
 * 用「压缩前后消息条数差」作为被替换条数，`coveredUserTurns` 作为覆盖轮次——
 * 两者都是快照/会话中现成的数据，不需要引入 token 计量。
 * 快照为空且 ok 表示「无可压缩内容」（AgentModelClient 已将 CONTEXT_NOT_COMPACTABLE
 * 在手动路径转为成功），与「压缩完成」区分展示。
 */
internal object AgentCompactionDetail {
    fun forResult(
        ok: Boolean,
        snapshot: AgentContextSnapshot?,
        historySizeBefore: Int,
        error: String?,
    ): String = when {
        ok && snapshot != null -> {
            val shadowed = (historySizeBefore - snapshot.messages.size).coerceAtLeast(0)
            "上下文压缩完成（已压缩 $shadowed 条历史，覆盖 ${snapshot.coveredUserTurns} 轮）"
        }
        ok -> "上下文无需压缩"
        else -> error?.takeIf { it.isNotBlank() } ?: "上下文压缩失败"
    }
}
