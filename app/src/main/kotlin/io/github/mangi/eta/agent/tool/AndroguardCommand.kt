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

    private val ARG_SEPARATOR = Regex("\\s+")

    /**
     * 只读开关白名单。
     *
     * 包含分析选项（`-a`/`-s`/`-r`…）与**纯输出控制**（verbose/quiet/no-progress 之类，
     * 只影响打印不影响写盘）；`-o` / `--output` / `--dump` 这类会写文件的选项故意不在其中。
     */
    private val ALLOWED_ARG_TOKENS = setOf(
        "-a", "--all", "-s", "--simple", "-r", "--recursive", "-d", "--debug",
        "-f", "--file-type", "--version",
        "-v", "--verbose", "-q", "--quiet", "--no-progress",
    )

    fun normalizeOperation(operation: String?): String =
        (operation ?: "").trim().lowercase().let { op ->
            if (op in ALLOWED_OPERATIONS) op else DEFAULT_OPERATION
        }

    fun isDexScan(operation: String?): Boolean =
        (operation ?: "").trim().lowercase() == DEX_SCAN_OPERATION

    /**
     * 透传给 androguard CLI 的额外参数：**逐 token 白名单**。
     *
     * 只做字符过滤是不够的：`-o /sdcard/out.xml` 全是合法字符，却能写文件 ——
     * 计划模式把 `androguard_analyze` 当只读工具放行，这条路径直接绕过只读约束。
     * 现在只接受明确列举的只读分析开关，出现任何不在名单里的 token 就整段丢弃。
     */
    internal fun sanitizeExtraArgs(raw: String): String {
        if (raw.isBlank()) return ""
        val tokens = raw.trim().split(ARG_SEPARATOR).filter { it.isNotEmpty() }
        if (tokens.isEmpty() || tokens.any { it !in ALLOWED_ARG_TOKENS }) return ""
        return " " + tokens.joinToString(" ")
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
