package io.github.mangi.eta.agent.model

import java.math.BigDecimal
import org.json.JSONArray
import org.json.JSONObject

/** 在工具执行前校验模型参数；这里只检查调用合同，不承担权限审批或安全策略。 */
internal class AgentToolCallValidator(tools: JSONArray) {
    private data class ToolSchema(
        val parameters: JSONObject,
        val root: JSONObject,
    )

    /**
     * XML 风格 tool call 的粘包残渣标记。
     *
     * 背景（2026-10-08 真机取证）：模型把参数写成 XML 形态并粘包，例如 action 值成了
     * `exec><parameter = command>…`。落在枚举字段上会被取值校验挡住，落到 command 这类
     * 自由文本字段则会被原样交给 shell 执行 —— 这里在统一入口兜住。
     *
     * 只在明确的粘包形态上触发：`grep '<parameter' config.xml` 含 `<parameter`
     * 但缺闭合标签与粘包前缀，不会被误伤。
     */
    private val xmlResidueMarkers = listOf(
        "</parameter>",
        "><parameter ",
        "<parameter name=",
        "</invoke>",
        "</function_calls>",
    )

    private val schemasByName: Map<String, ToolSchema> = buildMap {
        for (index in 0 until tools.length()) {
            val function = tools.optJSONObject(index)?.optJSONObject("function") ?: continue
            val name = function.optString("name").trim()
            val parameters = function.optJSONObject("parameters") ?: continue
            if (name.isNotBlank()) put(name, ToolSchema(parameters, parameters))
        }
    }

    fun validate(call: AgentModelClient.ToolCall): String? {
        val toolSchema = schemasByName[call.name]
            ?: return retiredToolGuidance(call.name) ?: "工具未在本次运行的能力目录中声明"
        val arguments = runCatching { JSONObject(call.argumentsJson.ifBlank { "{}" }) }
            .getOrElse { return "参数不是有效的 JSON object" }
        if (isRedactedPayload(arguments)) return REDACTED_REPLAY_GUIDANCE
        findXmlResidue(arguments)?.let { residue -> return xmlResidueGuidance(residue.first, residue.second) }
        return validateValue(
            value = arguments,
            schema = toolSchema.parameters,
            root = toolSchema.root,
            path = "arguments",
            depth = 0,
        )
    }

    /** 历史里敏感工具的参数会被脱敏成 {_redacted,_note,_fields}；模型原样重发时必须给出可执行引导。 */
    fun isRedactedReplay(call: AgentModelClient.ToolCall): Boolean {
        val arguments = runCatching { JSONObject(call.argumentsJson.ifBlank { "{}" }) }.getOrNull() ?: return false
        return isRedactedPayload(arguments)
    }

    private fun isRedactedPayload(value: JSONObject): Boolean {
        if (value.has(REDACTED_KEY)) return true
        return REDACTED_MARKERS.all { value.has(it) }
    }

    /** 递归检出参数值里的 XML 粘包残渣；返回命中的字段路径与标记。 */
    private fun findXmlResidue(value: Any?, path: String = ""): Pair<String, String>? = when (value) {
        is String -> xmlResidueMarkers.firstOrNull { marker -> value.contains(marker) }
            ?.let { marker -> path to marker }
        is JSONObject -> value.keys().asSequence().mapNotNull { key ->
            findXmlResidue(value.opt(key), if (path.isEmpty()) key else "$path.$key")
        }.firstOrNull()
        is JSONArray -> (0 until value.length()).asSequence().mapNotNull { index ->
            findXmlResidue(value.opt(index), "$path[$index]")
        }.firstOrNull()
        else -> null
    }

    /** 残渣命中的对症引导：点明字段与标记，并给出正确写法。 */
    private fun xmlResidueGuidance(path: String, marker: String): String {
        val field = path.ifBlank { "arguments" }
        return "参数「$field」的值里检测到 XML 标签残留（$marker）：这次工具调用被写成了 XML 风格。" +
            "请改用纯 JSON 字符串传参，形如 {\"action\":\"exec\",\"command\":\"ls -la\"}；" +
            "每个参数的值只放内容本身，不要包含 <parameter …> 这类标签。"
    }

    private fun validateValue(
        value: Any?,
        schema: JSONObject,
        root: JSONObject,
        path: String,
        depth: Int,
    ): String? {
        if (depth > MAX_SCHEMA_DEPTH) return "$path 的 Schema 引用层级过深"

        schema.optString("${'$'}ref").takeIf { it.isNotBlank() }?.let { reference ->
            val referenced = resolveReference(root, reference)
                ?: return "$path 的 Schema 引用无法解析：$reference"
            validateSchema(value, referenced, root, path, depth + 1)?.let { return it }
        }

        validateComposition(value, schema, root, path, depth)?.let { return it }

        if (schema.optBoolean("nullable", false) && isJsonNull(value)) return null
        val type = schema.opt("type")
        if (type != null && type != JSONObject.NULL && !matchesType(value, type)) {
            return "$path 类型应为 ${describeType(type)}"
        }

        if (schema.has("const") && !jsonEquals(schema.opt("const"), value)) {
            return "$path 必须等于 Schema 声明的固定值"
        }
        val enum = schema.optJSONArray("enum")
        if (enum != null && (0 until enum.length()).none { jsonEquals(enum.opt(it), value) }) {
            return "$path 不在允许值集合中"
        }

        return when (value) {
            is JSONObject -> validateObject(value, schema, root, path, depth)
            is JSONArray -> validateArray(value, schema, root, path, depth)
            is String -> validateString(value, schema, path)
            is Number -> validateNumber(value, schema, path)
            else -> null
        }
    }

    private fun validateComposition(
        value: Any?,
        schema: JSONObject,
        root: JSONObject,
        path: String,
        depth: Int,
    ): String? {
        schema.optJSONArray("allOf")?.let { branches ->
            for (index in 0 until branches.length()) {
                validateSchema(value, branches.opt(index), root, path, depth + 1)?.let { return it }
            }
        }
        schema.optJSONArray("anyOf")?.let { branches ->
            branchFailure(value, branches, root, path, depth, minimum = 1, maximum = Int.MAX_VALUE)
                ?.let { return it }
        }
        schema.optJSONArray("oneOf")?.let { branches ->
            branchFailure(value, branches, root, path, depth, minimum = 1, maximum = 1)
                ?.let { return it }
        }
        schema.opt("not").takeUnless { it == null || it == JSONObject.NULL }?.let { rejected ->
            if (validateSchema(value, rejected, root, path, depth + 1) == null) {
                val forbidden = forbiddenFieldsHit(value, rejected)
                return if (forbidden.isEmpty()) {
                    "$path 符合了 not 禁止的 Schema"
                } else {
                    "$path 含当前 action 不接受的字段：${forbidden.joinToString("、")}（请移除后重试）"
                }
            }
        }
        schema.opt("if").takeUnless { it == null || it == JSONObject.NULL }?.let { condition ->
            val branch = if (validateSchema(value, condition, root, path, depth + 1) == null) {
                schema.opt("then")
            } else {
                schema.opt("else")
            }
            if (branch != null && branch != JSONObject.NULL) {
                validateSchema(value, branch, root, path, depth + 1)?.let { return it }
            }
        }
        return null
    }

    /**
     * 分支组（anyOf / oneOf）不匹配时给出可诊断原因；满足约束则返回 null。
     *
     * 原实现只回一个布尔值，调用方把「0 个分支匹配」与「匹配数超出上限」折叠成
     * 同一句报错，且不指出字段 —— 实测 action 拼错、多传了不该有的字段、取值
     * 非法这三种完全不同的原因会得到同一句话，会话里只能靠猜（已多次发生）。
     * 现在分开报：超出上限 → 报实际匹配数；不足下限 → 附各分支的首个失败原因。
     */
    private fun branchFailure(
        value: Any?,
        branches: JSONArray,
        root: JSONObject,
        path: String,
        depth: Int,
        minimum: Int,
        maximum: Int,
    ): String? {
        var matches = 0
        val failures = mutableListOf<String>()
        var preferred: String? = null
        // 请求里显式给出的 action：与之同名分支的失败原因才是模型最该先看到的。
        val requestedAction = (value as? JSONObject)?.optString("action")?.takeIf { it.isNotBlank() }
        for (index in 0 until branches.length()) {
            val branch = branches.opt(index)
            val failure = validateSchema(value, branch, root, path, depth + 1)
            if (failure == null) {
                matches += 1
                if (matches > maximum) {
                    return "$path 同时符合多个互斥分支（要求至多 $maximum 个）：" +
                        "已判定 $matches 个，请只保留其中一种形态的字段"
                }
            } else {
                val label = branchLabel(branch) ?: "分支${index + 1}"
                val entry = "$label：$failure"
                if (requestedAction != null && label == "action=$requestedAction") preferred = entry
                if (failures.size < MAX_REPORTED_BRANCH_FAILURES) failures += entry
            }
        }
        if (matches >= minimum) return null
        // 请求的 action 值若根本不在任何分支的合法取值内 —— 直接点明值非法。
        // 列一堆无关分支的失败原因帮不上忙（真机实测：open_and_exec、foobar 都只会
        // 得到"某些分支不接受 command"之类的解释，模型还得自己比对取值表）。
        val (enumKey, enumValues) = unifiedEnumField(branches) ?: ("" to emptyList())
        if (requestedAction != null && enumKey == "action" && requestedAction !in enumValues) {
            return "$path 的 action 值「$requestedAction」不被接受；合法取值：${enumValues.joinToString("、")}"
        }
        // 与请求 action 同名的那条永远排最前：分支多时它最可能被上限截断，
        // 而它恰恰是模型最需要的信息。
        // 实测（v3.8.2 真机）：传合法的 exec 但缺 command 时，exec 分支的原因排在
        // 第二位；terminal 有 8 个分支，一旦它掉出前 3，模型就只剩无关分支的解释。
        val ordered = buildList {
            if (preferred != null) add(preferred)
            addAll(failures.filter { it != preferred })
        }.take(MAX_REPORTED_BRANCH_FAILURES)
        val omitted = branches.length() - ordered.size
        val detail = if (ordered.isEmpty()) "" else "；各分支失败原因：" + ordered.joinToString("；")
        val truncated = if (ordered.isNotEmpty() && omitted > 0) {
            "（共 ${branches.length()} 个分支，仅列前 ${ordered.size} 个）"
        } else {
            ""
        }
        return "$path 不符合任何分支（要求至少 $minimum 个）$detail$truncated" + allowedValuesHint(branches)
    }

    /**
     * 分支的简短标签。oneOf 分支多以某个枚举字段（如 `action`）区分，
     * 报「分支2」对排查毫无帮助，报「action=exec」才能一眼看出在说哪个分支。
     */
    private fun branchLabel(branch: Any?): String? {
        val (key, values) = singleEnumField(branch) ?: return null
        val first = values.firstOrNull() ?: return null
        return "$key=$first"
    }

    /**
     * 分支组若统一由单一枚举字段区分，附上全部合法取值。
     *
     * 实测（terminal 的 8 分支 oneOf 真机上连续两次踩中）：模型最需要的信息是
     * "这个参数到底能填什么"，而只列前几条分支原因反而看不到全集 —— 有会话因此
     * 反复试探 action 取值。这里把并集直接给出。
     */
    private fun allowedValuesHint(branches: JSONArray): String {
        val (key, values) = unifiedEnumField(branches) ?: return ""
        return "；「$key」的合法取值：${values.joinToString("、")}"
    }

    /**
     * 分支组若统一由单一枚举字段区分，返回该字段名与其取值并集；否则 null。
     *
     * 用于两处：报错末尾附合法取值全集；以及判断请求里给的值是否根本不合法。
     */
    private fun unifiedEnumField(branches: JSONArray): Pair<String, List<String>>? {
        val fields = (0 until branches.length()).mapNotNull { singleEnumField(branches.opt(it)) }
        if (fields.isEmpty()) return null
        val key = fields.first().first
        if (fields.any { it.first != key }) return null
        val all = fields.flatMap { it.second }.distinct()
        if (all.size <= 1) return null
        return key to all
    }

    /** 分支若"只用一个枚举字段作区分"，返回该字段名与其取值；否则 null。 */
    private fun singleEnumField(branch: Any?): Pair<String, List<String>>? {
        val properties = (branch as? JSONObject)?.optJSONObject("properties") ?: return null
        val keys = properties.keys().asSequence().toList()
        if (keys.size != 1) return null
        val key = keys.first()
        val enum = properties.optJSONObject(key)?.optJSONArray("enum") ?: return null
        val values = (0 until enum.length()).mapNotNull { enum.optString(it).takeIf { value -> value.isNotBlank() } }
        if (values.isEmpty()) return null
        return key to values
    }

    /**
     * `not` 校验失败时，找出实际命中的被禁字段名。
     *
     * 只覆盖最常见形态（`not.anyOf[].required` / `not.required`，terminal 的
     * 专属字段白名单就是这种）；无法归因时返回空列表，调用方回退到原文案。
     * 这样"多传了一个 environment"能直接报出来，而不是笼统的"符合了 not"。
     */
    private fun forbiddenFieldsHit(value: Any?, rejected: Any?): List<String> {
        val target = value as? JSONObject ?: return emptyList()
        val hits = mutableListOf<String>()
        fun collect(schema: Any?) {
            val object_ = schema as? JSONObject ?: return
            object_.optJSONArray("required")?.let { required ->
                for (index in 0 until required.length()) {
                    val name = required.optString(index)
                    if (name.isNotBlank() && target.has(name) && name !in hits) hits += name
                }
            }
            object_.optJSONArray("anyOf")?.let { branches ->
                for (index in 0 until branches.length()) collect(branches.opt(index))
            }
            object_.optJSONArray("allOf")?.let { branches ->
                for (index in 0 until branches.length()) collect(branches.opt(index))
            }
        }
        collect(rejected)
        return hits
    }

    /**
     * 已并入其他工具的旧名字，给出明确的迁移指引而不是笼统的"未声明"。
     *
     * run_command 与 terminal 的 Android 环境完全重叠，v3.2.0 起已从能力目录
     * 移除（执行侧与显示映射仍在）。历史会话与长期记忆里仍存有大量旧用法，
     * 只报"未在能力目录中声明"时模型会反复重试或绕道避开 —— 这里直接给出
     * 等价调用，让旧用法一次就迁移完成。
     */
    private fun retiredToolGuidance(name: String): String? = when (name) {
        "run_command" ->
            "run_command 已并入 terminal：请改用 terminal（action=\"exec\", environment=\"android\"）"
        "open_and_exec" ->
            "open_and_exec 是 terminal 的旧动作名：请改用 action=\"exec\""
        else -> null
    }

    /**
     * 报错时附上"实际收到的字段清单"。
     *
     * 原来只报第一个缺失字段（`缺少必填字段 revision`），无法区分两种完全不同的情况：
     * 模型压根没生成该字段，还是链路把参数丢/串了。附带形状后可以当场判断——
     * 已经不能用"字段缺失"这种单点信息去猜归因了。
     * 只输出键名与各值的类型/长度，不输出取值（错误信息里不落内容）。
     */
    private fun describeReceivedFields(value: JSONObject): String {
        val parts = mutableListOf<String>()
        val keys = value.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            parts += "$key(${describeFieldValue(value.opt(key))})"
        }
        return if (parts.isEmpty()) "（无）" else parts.joinToString("、")
    }

    private fun describeFieldValue(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is Boolean -> "bool"
        is Number -> "num"
        is String -> "str:${value.length}"
        is JSONArray -> "arr:${value.length()}"
        is JSONObject -> "obj:${value.length()}"
        else -> "?"
    }

    private fun validateObject(
        value: JSONObject,
        schema: JSONObject,
        root: JSONObject,
        path: String,
        depth: Int,
    ): String? {
        val size = value.length()
        schema.optInteger("minProperties")?.let { if (size < it) return "$path 的字段数不能少于 $it" }
        schema.optInteger("maxProperties")?.let { if (size > it) return "$path 的字段数不能超过 $it" }

        schema.optJSONArray("required")?.let { required ->
            val missing = (0 until required.length())
                .map { required.optString(it) }
                .filterNot { value.has(it) }
            if (missing.isNotEmpty()) {
                return "$path 缺少必填字段 ${missing.joinToString("、")}；" +
                    "已收到字段：${describeReceivedFields(value)}"
            }
        }

        schema.optJSONObject("dependentRequired")?.let { dependencies ->
            for (key in dependencies.keys()) {
                if (!value.has(key)) continue
                val required = dependencies.optJSONArray(key) ?: continue
                for (index in 0 until required.length()) {
                    val dependent = required.optString(index)
                    if (!value.has(dependent)) return "$path.$key 要求同时提供字段 $dependent"
                }
            }
        }

        val properties = schema.optJSONObject("properties")
        val patternProperties = schema.optJSONObject("patternProperties")
        val additionalProperties = schema.opt("additionalProperties")
        for (key in value.keys()) {
            val childPath = "$path.$key"
            val childValue = value.opt(key)
            var matched = false
            properties?.opt(key)?.takeUnless { it == JSONObject.NULL }?.let { childSchema ->
                matched = true
                validateSchema(childValue, childSchema, root, childPath, depth + 1)?.let { return it }
            }
            if (patternProperties != null) {
                for (pattern in patternProperties.keys()) {
                    val regex = runCatching { Regex(pattern) }.getOrNull() ?: continue
                    if (!regex.containsMatchIn(key)) continue
                    matched = true
                    val childSchema = patternProperties.opt(pattern) ?: continue
                    validateSchema(childValue, childSchema, root, childPath, depth + 1)?.let { return it }
                }
            }
            if (!matched) {
                when (additionalProperties) {
                    false -> return "$path 不允许额外字段 $key"
                    is JSONObject, is Boolean ->
                        validateSchema(childValue, additionalProperties, root, childPath, depth + 1)?.let { return it }
                }
            }
        }

        schema.opt("propertyNames").takeUnless { it == null || it == JSONObject.NULL }?.let { nameSchema ->
            for (key in value.keys()) {
                validateSchema(key, nameSchema, root, "$path 的字段名 $key", depth + 1)?.let { return it }
            }
        }
        return null
    }

    private fun validateArray(
        value: JSONArray,
        schema: JSONObject,
        root: JSONObject,
        path: String,
        depth: Int,
    ): String? {
        schema.optInteger("minItems")?.let { if (value.length() < it) return "$path 项目数不能少于 $it" }
        schema.optInteger("maxItems")?.let { if (value.length() > it) return "$path 项目数不能超过 $it" }
        if (schema.optBoolean("uniqueItems", false)) {
            for (left in 0 until value.length()) {
                for (right in left + 1 until value.length()) {
                    if (jsonEquals(value.opt(left), value.opt(right))) return "$path 不允许重复项目"
                }
            }
        }

        val prefixItems = schema.optJSONArray("prefixItems")
        if (prefixItems != null) {
            for (index in 0 until minOf(prefixItems.length(), value.length())) {
                validateSchema(value.opt(index), prefixItems.opt(index), root, "$path[$index]", depth + 1)
                    ?.let { return it }
            }
        }
        when (val items = schema.opt("items")) {
            is JSONObject -> {
                val start = prefixItems?.length() ?: 0
                for (index in start until value.length()) {
                    validateValue(value.opt(index), items, root, "$path[$index]", depth + 1)?.let { return it }
                }
            }
            is JSONArray -> {
                for (index in 0 until minOf(items.length(), value.length())) {
                    validateSchema(value.opt(index), items.opt(index), root, "$path[$index]", depth + 1)
                        ?.let { return it }
                }
            }
            false -> if (value.length() > (prefixItems?.length() ?: 0)) return "$path 不允许更多项目"
        }

        schema.opt("contains").takeUnless { it == null || it == JSONObject.NULL }?.let { contains ->
            val matches = (0 until value.length()).count { index ->
                validateSchema(value.opt(index), contains, root, "$path[$index]", depth + 1) == null
            }
            val minimum = schema.optInteger("minContains") ?: 1
            val maximum = schema.optInteger("maxContains") ?: Int.MAX_VALUE
            if (matches !in minimum..maximum) return "$path 中符合 contains 的项目数必须在 $minimum..$maximum 之间"
        }
        return null
    }

    private fun validateString(value: String, schema: JSONObject, path: String): String? {
        schema.optInteger("minLength")?.let { if (value.codePointCount(0, value.length) < it) return "$path 长度不能少于 $it" }
        schema.optInteger("maxLength")?.let { if (value.codePointCount(0, value.length) > it) return "$path 长度不能超过 $it" }
        schema.optString("pattern").takeIf { it.isNotBlank() }?.let { pattern ->
            val regex = runCatching { Regex(pattern) }.getOrNull()
                ?: return "$path 的 Schema pattern 无效"
            if (!regex.containsMatchIn(value)) return "$path 不符合 pattern $pattern"
        }
        return null
    }

    private fun validateNumber(value: Number, schema: JSONObject, path: String): String? {
        val number = value.toBigDecimal() ?: return "$path 不是有效数字"
        schema.optBigDecimal("minimum")?.let { if (number < it) return "$path 不能小于 $it" }
        schema.optBigDecimal("maximum")?.let { if (number > it) return "$path 不能大于 $it" }
        schema.optBigDecimal("exclusiveMinimum")?.let { if (number <= it) return "$path 必须大于 $it" }
        schema.optBigDecimal("exclusiveMaximum")?.let { if (number >= it) return "$path 必须小于 $it" }
        schema.optBigDecimal("multipleOf")?.takeIf { it.signum() != 0 }?.let { divisor ->
            if (number.remainder(divisor).compareTo(BigDecimal.ZERO) != 0) return "$path 必须是 $divisor 的倍数"
        }
        return null
    }

    private fun matchesType(value: Any?, declared: Any): Boolean {
        if (declared is JSONArray) {
            return (0 until declared.length()).any { matchesType(value, declared.optString(it)) }
        }
        val type = declared as? String ?: return true
        return when (type) {
            "object" -> value is JSONObject
            "array" -> value is JSONArray
            "string" -> value is String
            "boolean" -> value is Boolean
            "number" -> value is Number
            "integer" -> value is Number && value.toBigDecimal()?.stripTrailingZeros()?.scale()?.let { it <= 0 } == true
            "null" -> isJsonNull(value)
            else -> true
        }
    }

    private fun validateSchema(
        value: Any?,
        schema: Any?,
        root: JSONObject,
        path: String,
        depth: Int,
    ): String? = when (schema) {
        true -> null
        false -> "$path 被 false Schema 拒绝"
        is JSONObject -> validateValue(value, schema, root, path, depth)
        else -> "$path 的 Schema 节点无效"
    }

    private fun resolveReference(root: JSONObject, reference: String): Any? {
        if (!reference.startsWith("#")) return null
        if (reference == "#") return root
        if (!reference.startsWith("#/")) return null
        var current: Any? = root
        for (rawToken in reference.removePrefix("#/").split('/')) {
            val token = rawToken.replace("~1", "/").replace("~0", "~")
            current = when (current) {
                is JSONObject -> if (current.has(token)) current.opt(token) else return null
                is JSONArray -> token.toIntOrNull()?.let { current.opt(it) } ?: return null
                else -> return null
            }
        }
        return current
    }

    private fun jsonEquals(left: Any?, right: Any?): Boolean = canonicalJson(left) == canonicalJson(right)

    private fun canonicalJson(value: Any?): Any? = when (value) {
        null, JSONObject.NULL -> null
        is JSONObject -> value.keys().asSequence().sorted().associateWith { canonicalJson(value.opt(it)) }
        is JSONArray -> (0 until value.length()).map { canonicalJson(value.opt(it)) }
        is Number -> value.toBigDecimal()?.stripTrailingZeros()
        else -> value
    }

    private fun Number.toBigDecimal(): BigDecimal? = runCatching { BigDecimal(toString()) }.getOrNull()

    private fun JSONObject.optInteger(name: String): Int? =
        opt(name).takeIf { it is Number }?.let { (it as Number).toInt() }

    private fun JSONObject.optBigDecimal(name: String): BigDecimal? =
        (opt(name) as? Number)?.toBigDecimal()

    private fun describeType(type: Any): String = when (type) {
        is JSONArray -> (0 until type.length()).joinToString(" 或 ") { type.optString(it) }
        else -> type.toString()
    }

    private fun isJsonNull(value: Any?): Boolean = value == null || value == JSONObject.NULL

    private companion object {
        const val MAX_SCHEMA_DEPTH = 256

        /** 报错时最多附带的单分支失败原因条数（防止分支多时刷屏）。 */
        const val MAX_REPORTED_BRANCH_FAILURES = 3
        const val REDACTED_KEY = "_redacted"
        val REDACTED_MARKERS = listOf("_note", "_fields")

        /** 脱敏占位不是参数：取值不可恢复，唯一出路是重新取数。 */
        const val REDACTED_REPLAY_GUIDANCE =
            "参数是会话里的脱敏占位（_redacted/_note/_fields），不能原样重发，真实取值不会被恢复。" +
                "请改为重新取数：例如先 observe_screen 重新截图，再用返回的新路径调用 read_image；" +
                "或用真实参数重新调用该工具。禁止重复提交相同占位参数。"
    }
}
