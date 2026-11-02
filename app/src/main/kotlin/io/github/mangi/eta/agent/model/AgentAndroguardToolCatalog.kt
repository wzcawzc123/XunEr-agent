package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/** Androguard APK 静态分析工具（依赖 Linux 工具环境）。 */
internal object AgentAndroguardToolCatalog {
    fun appendTo(tools: JSONArray) {
        tools.put(
            AgentToolSchema.function(
                name = "androguard_analyze",
                description = "用 Androguard(Linux 工具环境) 静态分析一个 APK。apk_path 必须是 Linux 环境可见的绝对路径(共享存储用 /sdcard/...)。operation=axml 解析 Manifest(权限/组件) apkid 取包名/版本 sign 取签名指纹(默认全哈希) dex_strings 扫 DEX 字符串危险/敏感信号，默认 axml。需先在工具环境页安装 Androguard。",
                parameters = JSONObject()
                    .put("type", "object")
                    .put(
                        "properties",
                        JSONObject()
                            .put("apk_path", JSONObject().put("type", "string"))
                            .put("operation", JSONObject().put("type", "string").put("description", "axml|apkid|sign|dex_strings，默认 axml"))
                            .put("args", JSONObject().put("type", "string").put("description", "附加透传给 androguard 的原始参数，可选")),
                    )
                    .put("required", JSONArray().put("apk_path")),
            )
        )
    }
}
