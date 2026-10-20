package io.github.mangi.eta.agent.plan

import org.json.JSONArray
import org.json.JSONObject

/**
 * `enter_plan_mode` / `exit_plan_mode`：计划模式的两个闸门。
 *
 * 设计参考 Codex Plan、Kimi `EnterPlanMode`/`ExitPlanMode`、DSH `plan-mode`：
 * - 进入计划模式后只读调研；
 * - 完成后提交**计划正文 + 步骤**，进入 REVIEW，等用户批准；
 * - 批准后才进入 EXECUTING，按已批准的版本执行。
 *
 * 审批入口刻意做成**文本**（回复"批准/取消/其他=修改意见"），而不是阻塞式 UI：
 * 移动端聊天里用户随时可能在别处，阻塞式弹窗容易丢状态；文本入口天然持久化在历史里，
 * 也让 `/plan`、自动任务、外部入口复用同一条协议。后续可以再加 UI 快捷按钮，
 * 但协议不变。
 */
internal object AgentPlanModeTool {

    const val ENTER_NAME = "enter_plan_mode"
    const val EXIT_NAME = "exit_plan_mode"

    /** 计划正文上限：足够写清方案与验收标准，又不至于把上下文撑爆。 */
    const val MAX_PLAN_CHARS = 8_000

    fun enterSchema(): JSONObject = JSONObject()
        .put("name", ENTER_NAME)
        .put(
            "description",
            "进入计划模式：只读调研、不修改任何东西，完成后用 exit_plan_mode 提交计划供用户批准。" +
                "仅当用户明确要求先规划（例如说“先给方案/别急着改/计划一下”）或任务风险较高时调用；" +
                "简单任务不要进入。进入后请先探索与验证，不要边做边改。",
        )
        .put(
            "parameters",
            JSONObject()
                .put("type", "object")
                .put("properties", JSONObject())
                .put("required", JSONArray()),
        )

    /**
     * `steps` 直接复用 `todo_write` 的 items schema，避免两套步骤协议漂移。
     *
     * 语义差异：这里只接受 `pending`（提交的是"打算怎么做"，不是"已经做完"），
     * 由服务端强制校验；schema 保留完整 enum 只是为了让模型看到合法值。
     */
    fun exitSchema(): JSONObject = JSONObject()
        .put("name", EXIT_NAME)
        .put(
            "description",
            "提交计划供用户批准。plan 是给用户看的完整方案（目标、步骤、风险、验收标准）；" +
                "steps 是执行阶段的清单，必须全部为 pending。提交后本轮不要继续执行任何修改，" +
                "并把计划正文与审批提示完整告诉用户：回复「批准」执行，回复「取消」放弃，" +
                "其他内容视为修改意见。",
        )
        .put(
            "parameters",
            JSONObject()
                .put("type", "object")
                .put(
                    "properties",
                    JSONObject()
                        .put(
                            "plan",
                            JSONObject()
                                .put("type", "string")
                                .put("description", "完整计划正文（Markdown），最多 $MAX_PLAN_CHARS 字")
                                .put("maxLength", MAX_PLAN_CHARS),
                        )
                        .put("steps", stepsSchema()),
                )
                .put("required", JSONArray().put("plan").put("steps")),
        )

    private fun stepsSchema(): JSONObject =
        AgentPlanTool.schema()
            .getJSONObject("parameters")
            .getJSONObject("properties")
            .getJSONObject("items")

    /** @return 面向模型的文本；失败时以 `"失败："` 开头（与 todo_write 一致）。 */
    fun enter(
        modeStore: AgentPlanModeStore,
        planStore: AgentPlanStore,
        conversationId: String,
        nowMillis: Long,
    ): String {
        if (conversationId.isBlank()) return "失败：当前会话尚无标识，无法进入计划模式。"
        return when (modeStore.phaseBlocking(conversationId)) {
            AgentPlanPhase.PLANNING ->
                "已处于计划模式。请继续只读调研，完成后调用 $EXIT_NAME 提交计划。"
            AgentPlanPhase.REVIEW ->
                "计划已提交，正在等待用户批准；批准前不要执行任何修改。"
            AgentPlanPhase.EXECUTING ->
                "当前正在执行已批准的计划。如需重新规划，请先完成或取消当前计划，不要直接覆盖。"
            AgentPlanPhase.NORMAL -> {
                modeStore.enterPlanningBlocking(conversationId, "plan-$nowMillis", nowMillis)
                // 新计划从空白清单开始：旧清单属于上一个任务，留着会让模型/进度条误以为已完成一半。
                planStore.replaceBlocking(conversationId, emptyList(), nowMillis)
                "已进入计划模式。现在只能只读调研：不要修改文件、设备、记忆或发送任何操作。" +
                    "完成后调用 $EXIT_NAME(plan, steps) 提交计划，等待用户批准。"
            }
        }
    }

    /**
     * 提交计划：校验步骤 → 写入 todos（全部 pending）→ 记录计划正文与版本 → 进入 REVIEW。
     *
     * @return 面向模型的文本；失败时不改变任何状态。
     */
    fun exit(
        args: JSONObject,
        modeStore: AgentPlanModeStore,
        planStore: AgentPlanStore,
        conversationId: String,
        nowMillis: Long,
    ): String {
        if (conversationId.isBlank()) return "失败：当前会话尚无标识，无法提交计划。"
        if (modeStore.phaseBlocking(conversationId) != AgentPlanPhase.PLANNING) {
            return "失败：当前不在计划模式（PLANNING），不能提交计划；请先调用 $ENTER_NAME 或让用户输入 /plan。"
        }

        val markdown = args.optString("plan").trim()
        if (markdown.isEmpty()) return "失败：缺少 plan（计划正文）。"
        if (markdown.length > MAX_PLAN_CHARS) return "失败：plan 超过 $MAX_PLAN_CHARS 字，请精简。"

        val rawSteps = args.optJSONArray("steps") ?: return "失败：缺少必填参数 steps（数组）。"
        if (rawSteps.length() == 0) return "失败：steps 不能为空；计划至少要有 1 个执行步骤。"

        val items = ArrayList<AgentPlanItem>(rawSteps.length())
        for (index in 0 until rawSteps.length()) {
            val raw = rawSteps.optJSONObject(index) ?: return "失败：steps[$index] 不是对象。"
            val rawStatus = raw.optString("status").trim()
            val status = if (rawStatus.isEmpty()) {
                AgentPlanStatus.PENDING
            } else {
                AgentPlanStatus.fromWire(rawStatus)
                    ?: return "失败：steps[$index].status=\"$rawStatus\" 不合法；可用值：" +
                        AgentPlanStatus.entries.joinToString(" / ") { it.wire }
            }
            if (status != AgentPlanStatus.PENDING) {
                return "失败：steps[$index].status 必须是 pending；计划阶段不能预先把步骤标成已完成/进行中。"
            }
            val item = AgentPlanItem.create(
                index = index,
                id = raw.optString("id").takeIf { it.isNotBlank() },
                subject = raw.optString("subject").takeIf { it.isNotBlank() },
                activeForm = raw.optString("activeForm").takeIf { it.isNotBlank() },
                status = AgentPlanStatus.PENDING,
            ).getOrElse { return "失败：${it.message}" }
            items += item
        }
        val validated = AgentPlanItem.validateAll(items)
            .getOrElse { return "失败：${it.message}" }

        // 先写步骤再切阶段：若阶段写入失败，会话仍停在 PLANNING，模型可以重试 exit；
        // 反过来会出现"已进入 REVIEW、但 todos 还是上一版"的不可执行状态。
        planStore.replaceBlocking(conversationId, validated, nowMillis)
        val state = modeStore.submitBlocking(conversationId, markdown, nowMillis)

        return AgentPlanReceipt.build(
            header = buildString {
                append("计划 v${state.version} 已提交，共 ${validated.size} 步，等待用户批准。")
                append("\n回复「批准」开始执行；回复「取消」放弃；其他内容将作为修改意见。")
                append("\n在用户批准前，不要执行任何修改或设备操作。")
            },
            markdown = markdown,
        )
    }
}
