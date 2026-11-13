package io.github.mangi.eta.agent.model

import io.github.mangi.eta.data.repository.AgentMemoryStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * 持久记忆的读取工具与写入工具声明。
 *
 * ## 为什么写入主推「内容寻址」（replace_text）
 *
 * 模型对**行号**的记忆是不可靠的：分页读之后、别处插入一行之后、上下文被压缩之后，
 * 行号都会漂移。而"引用我刚读到的那段原文"是天然稳定的 —— 模型只需从读取结果里复制。
 * Anthropic 的 memory tool、Hermes、gemini-cli 的编辑工具都收敛到了同一个形状：
 * `old_text` 唯一匹配 + `new_text`。行号模式保留只为兼容，不再是推荐路径。
 *
 * ## 为什么 append 会被拒绝（同名标题）
 *
 * 注入侧只取**第一个** `# 核心记忆`：重复一个同名标题，第二份内容就会被静默丢弃，
 * 而且没有任何报错。所以同名标题在写入这层就拦掉，直接告诉模型改用 replace_text。
 */
internal object AgentMemoryToolCatalog {
    fun appendTo(tools: JSONArray, writable: Boolean = true) {
        tools
            .put(
                AgentToolSchema.function(
                    name = "memory_get",
                    description = "Read persistent cross-conversation memory from MEMORY.md. " +
                        "Core memory and the heading index are already injected at run start, so call this only to " +
                        "inspect details, answer what is remembered, or refresh before writing. " +
                        "Prefer `section` to fetch one whole section by heading; use `query` for just-in-time search; " +
                        "use start_line/max_chars only for paged reading. " +
                        "The result includes core_usage — the exact same budget math as the automatic injection, " +
                        "so a near-100% or truncated=true means the core section is being cut off.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "section",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("maxLength", 200)
                                        .put(
                                            "description",
                                            "Heading text from the heading index (leading '#' optional). " +
                                                "Returns that whole section with line numbers. Takes precedence over query/start_line.",
                                        ),
                                )
                                .put(
                                    "query",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("maxLength", 500)
                                        .put(
                                            "description",
                                            "Optional case-insensitive text to search for in the full memory file; " +
                                                "matched sections are returned with one line of context.",
                                        ),
                                )
                                .put(
                                    "start_line",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("minimum", 1)
                                        .put(
                                            "description",
                                            "1-based first line for paged reading when section and query are omitted; default 1.",
                                        ),
                                )
                                .put(
                                    "max_chars",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("minimum", AgentMemoryStore.MIN_READ_CHARS)
                                        .put("maximum", AgentMemoryStore.MAX_READ_CHARS)
                                        .put("description", "Maximum returned characters; default 12000, maximum 32000."),
                                ),
                        ),
                ),
            )
        if (!writable) return
        tools
            .put(
                AgentToolSchema.function(
                    name = "memory_write",
                    description = "Atomically update persistent MEMORY.md. Store only durable cross-conversation facts, " +
                        "preferences, relationships, and ongoing project context; never secrets, credentials, " +
                        "verification codes, or transient requests. Keep '# 核心记忆' short and current. " +
                        "PREFER mode=replace_text: copy the exact wording from your latest memory_get result into old_text. " +
                        "It survives line-number drift from paging, compression, and concurrent edits — line numbers do not. " +
                        "Use replace_section to swap a whole section by heading; append only for a genuinely new section " +
                        "(its heading must not already exist); clear wipes everything and needs the confirmation string. " +
                        "Every write keeps the previous version in history, so a bad edit can be rolled back.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "mode",
                                    JSONObject()
                                        .put("type", "string")
                                        .put(
                                            "enum",
                                            JSONArray()
                                                .put("replace_text")
                                                .put("replace_section")
                                                .put("replace_range")
                                                .put("append")
                                                .put("clear"),
                                        )
                                        .put(
                                            "description",
                                            "replace_text swaps one unique excerpt (recommended); " +
                                                "replace_section replaces or deletes a whole section by heading; " +
                                                "replace_range edits inclusive 1-based lines; " +
                                                "append adds a brand-new section; clear removes all memory.",
                                        ),
                                )
                                .put(
                                    "revision",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("minLength", 64)
                                        .put("maxLength", 64)
                                        .put(
                                            "description",
                                            "Exact SHA-256 revision from the run-start memory context or the latest memory_get result.",
                                        ),
                                )
                                .put(
                                    "old_text",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("maxLength", AgentMemoryStore.MAX_WRITE_CONTENT_CHARS)
                                        .put(
                                            "description",
                                            "Required for replace_text. An exact excerpt that appears exactly once in the file. " +
                                                "Copy it from the latest memory_get output; if it matches several places, " +
                                                "include the surrounding lines so it becomes unique.",
                                        ),
                                )
                                .put(
                                    "new_text",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("maxLength", AgentMemoryStore.MAX_WRITE_CONTENT_CHARS)
                                        .put(
                                            "description",
                                            "Required for replace_text. The replacement for old_text; " +
                                                "pass an empty string to delete it. Omitting the field is rejected " +
                                                "so a forgotten argument can never silently delete content.",
                                        ),
                                )
                                .put(
                                    "section",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("maxLength", 200)
                                        .put(
                                            "description",
                                            "Required for replace_section; the heading to replace (leading '#' optional). " +
                                                "Must match exactly one heading.",
                                        ),
                                )
                                .put(
                                    "start_line",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("minimum", 1)
                                        .put("description", "Required for replace_range; inclusive 1-based first line."),
                                )
                                .put(
                                    "end_line",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("minimum", 1)
                                        .put("description", "Required for replace_range; inclusive 1-based last line."),
                                )
                                .put(
                                    "content",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("maxLength", AgentMemoryStore.MAX_WRITE_CONTENT_CHARS)
                                        .put(
                                            "description",
                                            "Markdown for replace_section / replace_range / append, at most 3500 characters. " +
                                                "For clear pass \"${AgentMemoryStore.CLEAR_CONFIRMATION}\". " +
                                                "For replace_section an empty content deletes the section, and omitting " +
                                                "the field is rejected — pass \"\" explicitly to delete.",
                                        ),
                                ),
                        )
                        .put("required", JSONArray().put("mode").put("revision")),
                ),
            )
    }
}
