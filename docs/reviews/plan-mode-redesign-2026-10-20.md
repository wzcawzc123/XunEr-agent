# 计划模式重构方案：入口下沉、计划进对话流、Markdown 渲染

> 状态：**待评审**（2026-10-20）
> 触发：真机反馈三点 —— ① 输入栏计划按钮丑且挤占空间 ② 计划展开看不全、不能滚动
> ③ 计划是纯文本显示，与聊天输出不同款，整体与官方产品差距大。

## 1. 调研：主流 Agent 怎么做

| 工具 | 进入方式 | 形态 |
|---|---|---|
| Claude Code | **Shift+Tab** 或 `/plan` 前缀 | 权限模式切换 |
| Kimi Code CLI | Shift+Tab 切换 Normal ↔ Plan | 模式切换 |
| Gemini CLI | 循环 approval modes（Default→Auto-Edit→Plan） | 模式循环 |
| Cursor CLI | Agent → Plan → Ask | 模式循环 |
| Grok Build | Code → Plan → Ask | 模式循环 |
| Qwen Code | plan → default → auto-edit → auto → yolo | 模式循环 |
| Codex CLI | 仅命令 `/plan` | 命令 |
| Aider | `/code` `/ask` `/architect` | 命令 |

**两条结论**：

1. **十家里十家都由用户主动开启，没有一家让模型自行判断进入。**
   原因：Plan mode 本质是**权限边界切换**（只读 ↔ 可写）。让模型自行进入，
   等于让模型自行修改自己的权限边界——这是权限设计上的反模式，
   任何一款成熟产品都不会这么做。
2. **入口都是"模式切换"而不是"发起任务"**，且都足够轻（一个快捷键或一条命令）。

### 对「让模型自行判断」的回应

不能直接做，但可以做得接近：**模型建议，用户一键确认**。
模型在普通对话里判断任务复杂、需要先规划时，输出一段建议（文本协议，
与现有的「批准/取消」同款思路），UI 在该消息末尾渲染「进入计划模式」按钮，
用户点一下即进入。这样：

- 不用记命令、不用在工具栏找按钮（满足"不想手打"的原始诉求）；
- 决定权仍在用户手里（不破坏权限设计）；
- 入口出现在**用户正在阅读的位置**，而不是孤零零挂在工具栏。

另可加设置项「允许模型自动进入计划模式」（默认关闭），给想要全自动的人留路。

## 2. 现状问题（代码级）

| # | 问题 | 位置 | 证据 |
|---|---|---|---|
| P1 | 工具栏 pill 与这排图标按钮形态不一致（唯一带文字、26dp 高 ~70dp 宽），且该 Row **不滚动**，挤占右侧按钮空间 | `AgentChatInputBar.PlanModeChip` | 该排其余控件均为 `ChatInputActionSize` 的 `IconButton` |
| P2 | 审批条正文展开后 **`maxLines = Int.MAX_VALUE` 但没有滚动容器**，外层 InputBar 的 Column 不滚动 → 长计划被裁剪/顶爆布局 | `PlanApprovalBar` | 无 `verticalScroll` / `Modifier.heightIn(max=…)` |
| P3 | 计划正文用纯 `Text` 显示，**不是 Markdown**；而聊天气泡有完整 Markdown 渲染，工具卡也只有 `resultSummary` 纯文本 | `PlanApprovalBar` / `AgentWorkSteps:79` | 项目已有 `StaticMarkdown`（记忆页在用）未复用 |
| P4 | 计划正文同时出现在「工具卡」和「审批条」两处，各一份、都展示不全，用户要在两个半成品之间拼凑全文 | — | — |

## 3. 目标交互（对标 Claude Code + 本项目既有附件 chip）

### 3.1 入口：加号菜单 + 输入区可删除 chip（用户选定方案）

不复用工具栏位置——那一排保持原样。入口放进**加号（附件）菜单**，与截图里
「模式 → 计划 Plan」的思路一致；选中后在输入区挂一个**可删除 chip**，
形态与附件引用 chip（`PendingFileReferenceStrip`）完全同款：

```
点加号 → 菜单多一项「计划」
   ↓
输入区出现：  [📋 计划  ×]        ← 与附件 chip 同款，点 × 可删
   ↓
输入框提示变为「描述任务，我先只读调研，再给你方案」
   ↓
不想要了 → 点 chip 上的 ×  → 直接退出计划模式，不发消息、不起 run
```

- chip 上的 `×` 即关闭：与附件引用删除同语义，用户无需学习
- 流式中禁用（与附件一致）
- `/plan` 文本入口保留（语音/外接输入）

### 3.2 模型不会自行进入，但会「建议 + 一键进入」

Plan mode 是权限边界切换（只读 ↔ 可写），**不能让模型自行进入**（调研见 §1）。
替代：模型在普通对话中判断任务复杂时，输出一段建议（文本前缀约定），
UI 在该消息末尾渲染「进入计划模式」按钮，用户点一下即进入。

### 3.3 计划就绪 → 审批

计划作为**对话流里的一条消息**出现（Markdown 全渲染、天然可滚动）：

```
┌─────────────────────────────┐
│ 📋 实施计划                  │
│ # 目标                      │
│ 把通知权限开到可用            │
│ ## 步骤                     │
│ 1. 读取当前权限状态          │
│ 2. 打开设置页                │
│ ...                        │
└─────────────────────────────┘
┌─────────────────────────────┐
│ 计划已就绪，等待你确认        │  ← 精简审批条：只有标题+两个按钮
│ [批准并执行]  [取消计划]      │     不再放正文（正文在上方消息里）
└─────────────────────────────┘
```

审批语义（**已实现，本次不改**）：批准前工具层硬拦截一切修改类工具；
可批准 / 取消 / 直接打字提修改意见（自动回到规划）。

## 4. 改动方案

### 4.1 入口：删工具栏 pill，改「加号菜单 + 输入区 chip」

| 动作 | 文件 |
|---|---|
| **删除** `PlanModeChip`，输入栏那一排恢复原样（附件/思考/上下文/语音/模型/发送，全是原版 `IconButton`） | `AgentChatInputBar.kt` |
| 加号菜单新增一项「计划」（`AgentChatFileAttachments.kt` 的菜单列表），流式中禁用 | `AgentChatFileAttachments.kt` |
| 输入区新增 `PlanModeStrip`：显示 `[📋 计划 ×]`，**复用 `PendingFileReferenceStrip` 的 chip 形态与删除语义** | `AgentChatInputBar.kt` |
| 点 chip 的 `×` → `exitPlanMode()`（已实现：纯本地清 phase/清单/正文，零消息零 run） | `AgentAppState.kt` |
| `/plan` 文本入口**保留**（语音/外接输入） | 不动 |

### 4.2 计划呈现：进对话流 + Markdown

| 动作 | 文件 |
|---|---|
| `exit_plan_mode` 的回执改为**结构化**：`plan_markdown` 独立字段 + 步骤摘要，UI 识别后渲染成一条专用计划消息 | `AgentPlanModeTool.kt` / `AgentTraceFormatter.kt` |
| 计划消息用 `StaticMarkdown`（与聊天气泡同款渲染） | 消息渲染层 |
| 审批条**去掉正文**，只留标题 + 两个按钮 + 「完整计划见上方」 | `AgentChatInputBar.PlanApprovalBar` |

### 4.3 审批条修复（P2 的即时止血）

即便按上面重构，审批条也不该再展示正文。若短期保留正文，必须二选一：
`Modifier.heightIn(max = 200.dp) + verticalScroll`，或干脆只显示前 3 行 + 「展开」跳到对话流里的计划消息。

## 5. 不改动的部分（重要）

以下逻辑层已经过 133 例测试验证，**不做改动**：

- `AgentPlanModeStore` / `AgentPlanModeRouter` / `AgentPlanModePolicy`（状态机、文本协议、只读白名单）
- `conversation_plans` 表与迁移
- 版本绑定、范围漂移、注入边界

本次是**纯 UI/呈现层重构**，不碰权限与状态逻辑 —— 这也是它虽然看起来动静大、实际风险低的原因。

## 6. 工作量与风险

| 阶段 | 内容 | 风险 |
|---|---|---|
| 1 | 删 toolbar pill；加号菜单加「计划」；输入区 `PlanModeStrip`（chip + × 删除）；审批条去正文（含 heightIn 止血） | 低，纯 UI 删/加 |
| 2 | 计划消息结构化 + Markdown 渲染（替换工具卡纯文本） | 中，动消息渲染 |
| 3 | 模型建议前缀 + 消息内一键进入按钮 | 中，新增协议 |

每阶段可独立发版，建议按 1 → 2 → 3 顺序，每阶段跑全量测试。

**回退**：每阶段独立提交，`git revert` 单笔即可；逻辑层未动，不影响数据兼容。

## 7. 已确认的决定

1. **入口**：✅ 加号菜单项 + 输入区可删除 chip（用户选定，复用附件 chip 形态，工具栏恢复原样）
2. **模型自动进入**：❌ 不做（权限边界不能让模型自行切换）；改为「模型建议 + 用户一键进入」
3. **审批**：保持现状（工具层硬拦截 + 批准/取消/修改意见三选一），本次不改

## 8. 待确认

- **阶段 2 的计划消息形态**：A) 对话流里独立计划消息卡（推荐，Markdown 全渲染、天然可滚动）B) 仍走工具卡但改 Markdown
- **实施节奏**：三个阶段一次全做完，还是先做阶段 1（入口改造 + 审批条止血）看效果再继续？

---

## 9. 实施记录（2026-10-20 晚，三阶段一次完成）

### 阶段 1：入口改造 ✅
- 删除 `PlanModeChip`（输入栏那一排恢复全是 `ChatInputActionSize` 图标按钮）
- `AgentAttachmentPickerButton` 加号菜单新增第 6 项：未进入时「计划」，已进入时「退出计划」
- 新增 `PlanModeStrip`（`AgentChatFileAttachments.kt`）：42dp 高 chip、14dp 圆角、
  图标 + 文字 + 28dp 圆形 × 删除区 —— 与 `PendingFileReferenceStrip` 完全同款
- `PlanApprovalBar` 去掉正文预览（删除 `preview` 参数、`expanded` 状态、`maxLines` 逻辑），
  只留标题 + 一句提示 + 两个按钮
- `planPreview` 全链路删除（InputBar/Body/Scaffold/BottomBar/Screen/UiState/AppState）

### 阶段 2：计划进对话流 + Markdown ✅
- 新增 `AgentPlanReceipt`（纯 JVM）：`build(header, markdown)` / `parse(summary)`
  用 `[ETA_PLAN_BEGIN]` / `[ETA_PLAN_END]` 包裹正文（不用 `---`，模型可能在计划里写分隔线）
- `exit_plan_mode` 回执改用结构化标记
- `ToolActivityDetail`：识别到计划回执 → 头部普通文本 + 正文 `StaticMarkdown`
  （与聊天气泡同款渲染），**不再受 `MAX_RESULT_LINES` 截断**
- `outcomeLine()`：计划回执只取回执头，避免折叠行下面漏出正文尾巴或结束标记

### 阶段 3：模型建议 + 一键进入 ✅
- 新增 `AgentPlanSuggestion`（纯 JVM）：最后一行 `[[PLAN_SUGGESTION]]` + 理由 → 拆正文与理由
- `AgentPromptBuilder`：普通阶段注入协议说明（计划阶段不注入，避免与只读约束重复）
- `PlanSuggestionBar` 渲染在助手消息末尾（含理由 + 「计划」按钮）
- 复制按钮与正文渲染都用去掉标记后的内容

### 测试
新增 3 个测试类（纯 JVM，无需 Robolectric）：
- `AgentPlanReceiptTest`（6 例）：正反运算、正文含分隔线、标记不成对、空正文、多标记取第一对
- `AgentPlanSuggestionTest`（7 例）：最后一行才算、带/不带理由、标记后有空行、协议文案约束
- `PlanModeUiContractTest`（5 例）：源码断言锁「入口不在工具栏 / 加号菜单有项 / 有可删除 chip /
  审批条不承载正文 / 计划正文走 Markdown」
- `AgentPromptBuilderTest`：系统消息 3→4 条，并把按索引取消息改为**从尾部取**（以后再加系统提示不会坏）
