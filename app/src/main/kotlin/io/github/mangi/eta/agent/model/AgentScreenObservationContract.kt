package io.github.mangi.eta.agent.model

import org.json.JSONObject

/** 屏幕观察工具在模型 schema、执行器与运行轨迹之间共享的默认合同。 */
internal object AgentScreenObservationContract {
    const val DEFAULT_INCLUDE_SCREENSHOT = false
    const val DEFAULT_INCLUDE_UI_TREE = true
    const val DEFAULT_MAX_NODES = 60
    const val MIN_MAX_NODES = 1
    const val MAX_MAX_NODES = 120

    /** 无障碍树节点数低于该值时，几乎可以肯定目标控件不在树里（实测：KernelSU 模块页 11、WebUI 6）。 */
    const val SPARSE_TREE_THRESHOLD = 12

    /**
     * 树稀疏时给模型的结构化提示：目标不可见 → 只能用截图像素坐标 → 点完必须复查。
     * 以前模型会反复尝试 tap_element 或盲猜坐标，且点完直接相信"成功"。
     */
    fun sparseTreeNote(nodeCount: Int, treeIncluded: Boolean): String {
        if (!treeIncluded || nodeCount >= SPARSE_TREE_THRESHOLD) return ""
        return "\n\n[无障碍树稀疏：本次只返回 $nodeCount 个节点，目标控件很可能不在树里。" +
            "不要用 tap_element。注意：你看到的截图经视觉管线渲染后比例未知（真机实测约缩小 6%），" +
            "直接在图上目测像素会系统性偏左上（实测点偏 166px 导致五连点空）——coordinate_contract 的 scale 1:1 描述的是图片文件，不是你眼中的渲染。" +
            "精确坐标必须程序化求取：root screencap 落盘目标区域截图，再用 python PIL 分析目标文字/按钮的像素 bbox 取中心，" +
            "禁止连续目测重试；两次目测未命中就强制切换到像素分析。" +
            "点击后必须重新 observe_screen 确认是否生效，连续两次没有变化就停止重试并说明情况。]"
    }

    data class Options(
        val includeScreenshot: Boolean,
        val includeUiTree: Boolean,
        val maxNodes: Int,
    )

    fun resolve(arguments: JSONObject): Options = Options(
        includeScreenshot = arguments.optBoolean(
            "include_screenshot",
            DEFAULT_INCLUDE_SCREENSHOT,
        ),
        includeUiTree = arguments.optBoolean(
            "include_ui_tree",
            DEFAULT_INCLUDE_UI_TREE,
        ),
        maxNodes = arguments.optInt("max_nodes", DEFAULT_MAX_NODES),
    )
}
