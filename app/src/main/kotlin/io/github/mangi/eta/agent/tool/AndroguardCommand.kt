package io.github.mangi.eta.agent.tool

/**
 * Androguard 命令构造（纯逻辑，可单测）。
 *
 * axml/apkid/sign 走 CLI 子命令（白名单，避免任意 CLI 子命令注入）；
 * dex_strings 不是 androguard CLI 子命令，走 venv python 内联脚本扫 DEX 字符串/危险信号；
 * apk 路径与脚本均用 POSIX 单引号转义，防止空格/特殊字符破坏命令行。
 */
internal object AndroguardCommand {
    const val DEFAULT_OPERATION = "axml"
    const val DEX_SCAN_OPERATION = "dex_strings"
    private val ALLOWED_OPERATIONS = setOf("axml", "apkid", "sign")
    private const val ANDROGUARD_PYTHON = "/opt/eta/uv-tools/androguard/bin/python"

    fun build(operation: String, apkPath: String, extraArgs: String): String {
        val op = normalizeOperation(operation)
        val apk = shellQuote(apkPath)
        val extra = sanitizeExtraArgs(extraArgs)
        return "androguard $op$extra $apk"
    }

    /** DEX 字符串危险/敏感信号扫描：用 androguard venv python 内联脚本，有界输出。 */
    fun buildDexScan(apkPath: String, limit: Int): String {
        val lim = limit.coerceIn(1, 200)
        val apk = shellQuote(apkPath)
        return "$ANDROGUARD_PYTHON -c ${shellQuote(DEX_SCRIPT)} $apk $lim"
    }

    fun normalizeOperation(operation: String?): String =
        (operation ?: "").trim().lowercase().let { op ->
            if (op in ALLOWED_OPERATIONS) op else DEFAULT_OPERATION
        }

    fun isDexScan(operation: String?): Boolean =
        (operation ?: "").trim().lowercase() == DEX_SCAN_OPERATION

    /**
     * 对透传给 androguard CLI 的额外参数做白名单校验：仅允许常规 CLI 字符
     * （字母/数字/=._:/-/空格/逗号）。一旦出现 shell 元字符（;|&$()<> 引号等），
     * 整个参数段丢弃，防止命令注入。
     */
    internal fun sanitizeExtraArgs(raw: String): String {
        if (raw.isBlank()) return ""
        val allowed = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789=._:/- ,"
        val safe = raw.all { it in allowed }
        if (!safe) return ""
        return " " + raw.trim()
    }

    /** POSIX 单引号转义：单引号内无法直接出现单引号，用 '\'' 表示。 */
    fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private val DEX_SCRIPT: String = """
import sys,json
from loguru import logger; logger.remove()
from androguard.core.apk import APK
from androguard.core.dex import DEX
ap=sys.argv[1]
limit=int(sys.argv[2]) if len(sys.argv)>2 else 50
a=APK(ap)
dexlist=list(a.get_all_dex())
danger=('runtime','exec(','getpackagemanager','startactivity','webview','loadurl','/system/','/bin/sh','anythink','topon','com.anythink','base64','aes/','des/','md5','jsonobject','reflection')
hits=[];total=0
for dex in dexlist:
    dvm=DEX(dex)
    for s in dvm.get_strings():
        if not s: continue
        total+=1
        if any(k in s.lower() for k in danger):
            hits.append(s[:120])
            if len(hits)>=limit: break
    if len(hits)>=limit: break
print(json.dumps({'dex_count':len(dexlist),'scanned':total,'hit_count':len(hits),'hits':hits[:limit]},ensure_ascii=False))
"""
}
