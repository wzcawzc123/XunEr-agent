package io.github.mangi.eta.agent.autotask

/**
 * 自动任务该用哪一份会话历史。
 *
 * ## 为什么需要单独抽出这个决策
 *
 * 修一个真机 bug 时发现：离线执行路径读的是 `conversations.history_json`，
 * 而这张表里存的是 `[]`（真正的历史在 `conversation_context_checkpoints.history_json`）。
 * 于是模型在离线执行时**看不到任何上下文** —— 结果质量差，而且前缀与正常对话不一致，
 * 缓存 100% 击穿。
 *
 * 这个坑之所以没人发现，是因为主路径（投递）用的是 UI 层的 `homeState.history`，一直是对的；
 * 只有「App 不在前台」的降级路径才走错。
 *
 * 于是把「选哪份 history」变成一个**纯函数**：行为可断言、可回归。
 */
internal object AutoTaskHistorySource {

    /**
     * 选择用于自动任务的历史 JSON 文本。
     *
     * 优先 checkpoint：它才是「发给模型的历史」，与正常对话同源 ——
     * 只有用它，离线执行的前缀才会和主路径一致（缓存才能命中）。
     *
     * `"[]"` 与空白都视为"没有内容"（数据库里空历史就是存成 `[]` 的），
     * 不能当成有效历史，否则解码出空列表却以为加载成功。
     *
     * @return 可解码的 JSON 文本；两者都没有内容时返回 null
     */
    fun pick(checkpointJson: String?, conversationJson: String?): String? =
        checkpointJson.usable() ?: conversationJson.usable()

    private fun String?.usable(): String? =
        this?.takeIf { it.isNotBlank() && it.trim() != "[]" }
}
