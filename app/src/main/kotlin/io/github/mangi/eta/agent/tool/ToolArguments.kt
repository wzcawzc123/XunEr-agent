package io.github.mangi.eta.agent.tool

import org.json.JSONException
import org.json.JSONObject

// Android 的 getString 会把 JSON null、对象和数组转成文本；写操作必须保留真实文本语义。
internal fun JSONObject.requiredText(name: String): String =
    opt(name) as? String ?: throw JSONException("$name 缺失或不是文本")

/**
 * 必须**显式给出**、但允许空串的文本参数。
 *
 * 用于「缺省等于删除」的语义（如 replace_text 的 new_text、replace_section 的 content）：
 * 模型少传一个字段就把内容删掉，还回 ok:true，只能靠历史回滚兜底，代价太大。
 * 这里把「没给」和「给了空串」区分开——空串仍然合法，表示确实要删。
 */
internal fun JSONObject.explicitText(name: String): String =
    if (!has(name)) {
        throw JSONException("$name 必须显式给出；若要删除该处内容，请显式传空字符串 $name=\"\"")
    } else {
        requiredText(name)
    }
