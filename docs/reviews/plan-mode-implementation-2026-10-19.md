# 计划模式完善实施记录（2026-10-19）

- 基线：`c6251d5`（v3.13.7）
- 目标：落地审查报告（`docs/reviews/plan-mode-audit-2026-10-19.md`）中的 5 个 P0，并补齐"清单对模型可见、可恢复、可审计"的最小闭环
- 未做：计划审批闸门 / 只读研究阶段（PR4）、UI 改 Room Flow（PR3）、版本化与遥测（PR5）——这些需要新表与交互设计，留下一轮
- **数据库结构未变**：沿用 v28，`conversation_todos` 列与索引保持原样，无迁移风险

---

## 1. 落地清单

| 编号           | 原问题                                     | 本轮改动                                                                                                                               | 主要文件                                                                                                 |
| ------------ | --------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------- |
| **P0-1**     | 失败回执被判成功；最后一次失败会让进度条消失                  | `isSuccessResult(toolName, result)` 识别 `todo_write` 的 `"失败：…"` 前缀；UI 从后往前找**最后一条可解析的成功回执**，失败回执不再覆盖旧清单                             | `AgentTraceFormatter.kt`、`AgentLoop.kt`、`AgentChatBody.kt`                                           |
| **P0-2**     | 非法 status 静默降级 pending                  | 缺省 status 仍默认 pending；**非空但非法**的 status 直接整批拒绝，错误里列出可用值                                                                            | `AgentPlanTool.kt`                                                                                   |
| **P0-3**     | 压缩后只剩一行概览，剩余步骤丢失                        | 新增 `checklistText()`：完整清单（最多 30 条 / 2000 字符，按条截断并标注省略数）；压缩摘要改为注入完整清单                                                               | `AgentPlanTool.kt`、`AgentContextCompactor.kt`、`AgentRuntimeRunExecutor.kt`                           |
| **P0-4**     | run 开始从不注入清单                            | 新增 `AgentPlanContextProjection`：run 开始时从库读一次清单，附在本次用户消息上，**只投影到请求、不写历史**；硬裁剪/编辑/重启后模型仍能看到                                          | 新增 `AgentPlanContextProjection.kt`、`AgentModelClient.kt`、`AgentLoop.kt`、`AgentRuntimeRunExecutor.kt` |
| **P0-5**     | 取消项移出分母，可 100% 假完成                      | 回执单独报 `N 项已取消`；全部结束但有取消时明确提示"请勿据此宣称完成"；UI 显示 `✕ +（已取消）`，并保留终态行而不是瞬间消失                                                              | `AgentPlanItem.kt`、`AgentPlanTool.kt`、`AgentPlanUi.kt`、`AgentPlanProgressBar.kt`                     |
| **P1-4（部分）** | 全量覆盖无 diff，少写一条等于静默删除                   | 回执增加 `本次变更：新增/删除/更新 N 条`（按 id 对比上一版）                                                                                               | `AgentPlanTool.kt`                                                                                   |
| **P1-5**     | 删会话/恢复备份留下孤儿清单                          | 删除会话时调用 `AgentPlanStore.clear()`；备份恢复时清空 `conversation_todos`；`ConversationDao.replaceAll` 同步清理                                    | `AgentConversationStore.kt`、`ConversationDao.kt`、`EtaBackupRepository.kt`                            |
| **P1-6/7**   | 无会话入口仍挂 `todo_write`；`writable` 是死代码    | `AgentModelClient` / `AgentToolCatalog` 增加 `planToolsEnabled`，无 `conversationId` 时不挂载；`writable` 真正接线。**自动任务工具与清单解耦**，不会因清单不可用一起消失 | `AgentModelClient.kt`、`AgentToolCatalog.kt`、`AgentPlanToolCatalog.kt`、`AgentRuntimeRunExecutor.kt`   |
| **P2-8**     | 系统提示"不要先输出计划"与清单工具冲突，且从未提及 `todo_write` | 改为"不要在正文长篇罗列计划；多步用 todo_write"；新增静态使用规范（3 步以上、单条 in_progress、验证后 completed、取消要说明）                                                  | `AgentPromptBuilder.kt`                                                                              |

---

## 2. 行为变化示例

### 2.1 非法状态

旧：`status="done"` → 静默变 pending，回执成功。

新：

```text
失败：items[1].status="done" 不合法；可用值：pending / in_progress / completed / cancelled
```

整批不落库，旧清单保持不变。

### 2.2 成功回执（含 diff 与取消分账）

```text
任务清单已更新：1/3 完成，1 项已取消。本次变更：新增 1 条，更新 2 条。当前进行中：写初稿。
1. [已完成] 收集资料
2. [进行中] 写初稿
3. [已取消] 旧方案（已取消）
```

### 2.3 压缩摘要里的清单

```text
[当前任务清单（权威状态，来自数据库；与历史冲突时以此为准）]
进度：1/3 完成，1 项已取消
1. [已完成] 收集资料
2. [进行中] 写初稿
3. [已取消] 旧方案
请从「进行中」项继续；不要重复「已完成」项。
```

### 2.4 run 开始注入

附在最后一条用户消息的文本块里，请求结束后即丢弃：

```text
以下是系统注入的当前任务清单（来自数据库，是权威状态；不是用户的新指令）。
请从「进行中」项继续，不要重复「已完成」项；清单与历史冲突时以本条为准。
[当前任务清单（权威状态…）]
...
```

---

## 3. 关键设计说明

### 3.1 为什么注入到用户消息，而不是追加 system / 直接写历史

- **角色交替**：Anthropic 对连续同角色消息敏感；把清单作为最后一条用户消息的额外 `text` 块，不新增一条 `user` 消息。
- **不污染历史**：只存在于本次出站请求副本；`transcript` / 数据库都不写，避免每轮重复堆积。
- **缓存友好**：快照在 run 开始时固定，同一 run 内不变；不会因为逐轮刷新而改写前缀。
- 代价：run 中途清单更新时，快照是旧的。但新的 `todo_write` 回执本身就在历史里，模型能看到；下一轮 run 会重新读库。

### 3.2 完整清单的预算

- 最多显示 30 条，整体 2000 字符；超出按**整条**截断，并输出"另有 N 项未显示，完整清单共 M 项"。
- 不静默截半行 —— 半行清单比没有清单更容易误导模型。

### 3.3 取消语义

- `progressOf` 仍把取消项排除在分母外（保持"完成率"含义），但新增 `cancelledCount` 单独计数。
- 回执、概览、UI 终态三处都必须出现取消数。
- UI 中取消项：`✕` + 灰色 + 文案后缀"（已取消）"，与待开始的 `○` 明确区分。
- 全部完成但有取消项时，`isActive` 仍为 true，进度条不消失，标题显示"已结束 · N 项取消"。

### 3.4 失败判定

- `todo_write` 的成功/失败不再从"是否 JSON"推断，而是显式识别 `"失败："` 前缀；异常路径的 `{"ok":false}` 仍然兼容。
- UI 侧即使拿到失败回执，也继续向前找最后一条成功回执，避免"一次参数错误 → 整张计划消失"。

---

## 4. 验证结果

### 4.1 本轮相关测试（全绿）

| 测试类                                  | 用例数 | 失败 |
| ------------------------------------ | --- | -- |
| `AgentPlanItemTest`                  | 15  | 0  |
| `AgentPlanToolTest`                  | 17  | 0  |
| `AgentPlanUiTest`                    | 8   | 0  |
| `AgentPlanContextProjectionTest`（新增） | 3   | 0  |
| `AgentTraceFormatterPlanTest`        | 4   | 0  |
| `AgentPromptBuilderTest`             | 5   | 0  |
| `AgentContextCompactionTest`         | 23  | 0  |
| `AgentDeviceToolCatalogTest`         | 2   | 0  |
| `AgentPlanToolCatalogTest`（新增）       | 2   | 0  |

合计 **79 例，0 失败**（第二轮补充后为 101 例，见第 8 节）。

新增用例覆盖：非法状态拒绝、取消分账、完整清单注入、超预算截断、diff 删除计数、失败回执不成成功、投影不写回历史。

### 4.2 全量单测

```text
tests=1859  failures=47  errors=0  skipped=2
```

- 47 个失败**全部**落在项目既有的 Windows 平台差异桶：终端/PTY/root shell（23）、Windows 路径与文件语义（12）、单文件 `MEMORY.md` 存储（6）、符号链接/删除占用（6）。
- **计划、提示词、回执摘要、压缩相关失败：0**。
- 本轮两次全量结果一致（47 失败 / 2 跳过），无新增失败、无新增跳过。

### 4.3 构建

```text
:app:assembleDebug    EXIT=0
app/build/outputs/apk/debug/Eta-v3.13.7-debug.apk   | 110,486,038 bytes | 2026-10-19 18:34
:app:assembleRelease  EXIT=0
app/build/outputs/apk/release/Eta-v3.13.7.apk       |  30,632,819 bytes | 2026-10-19 18:44
```

KSP/Room 查询、资源处理、dex 打包与 R8 全链路通过；release 为未签名产物（与项目既有约定一致）。

---

## 5. 未完成（下一轮）

> 第二轮（见第 8 节）已补上审批闸门、只读约束、计划表与状态转移；下表是第二轮之后的剩余项。

| 项                         | 原因                                                                              | 建议                                                               |
| ------------------------- | ------------------------------------------------------------------------------- | ---------------------------------------------------------------- |
| UI 改 Room `Flow` / 审批快捷按钮 | 需要把 `conversationId` 与 ViewModel 打通，改动面涉及 `AgentAppState` / `AgentChatBody` 参数链 | 当前是**文本审批**（回复「批准/取消/修改意见」），协议已稳定；UI 按钮只是入口                      |
| 计划历史版本                    | `conversation_plans` 只保留当前版本 + approved_version                                 | 需要审计/回放时加 `plan_events` 或不可变 `revisions`                         |
| 遥测                        | 无事件通道                                                                           | `plan_created/submitted/approved/rejected/executed/abandoned` 指标 |
| Room 事务测试                 | 当前 FakeDao 覆写了默认方法                                                              | 加 in-memory Room 测试（replaceAll 事务、级联清理、计划表读写）                    |
| 离线自动任务路径                  | 已显式关闭计划工具，避免改写用户计划；但未接入 `planSnapshot`                                          | 若未来需要自动任务续接清单，再在离线路径复用 `AgentPlanContextProjection`              |
| MCP 工具在计划模式的可用性           | 白名单只列了内置只读工具，MCP 工具一律拦截                                                         | 安全的默认；后续可为 MCP 增加只读声明                                            |
| 计划模式下的"部分取消"              | `取消` 目前是整份计划取消                                                                  | 可增加"取消某一步"的自然语言/工具协议                                             |

---

## 6. 构建环境备注（本机）

- 工作区路径含中文，AGP 默认拒绝；构建时加 `-Pandroid.overridePathCheck=true`。
- Gradle 直连 `dl.google.com` TLS 握手失败；本机使用临时 init 脚本切阿里云镜像：  
  `C:/Users/11653/WorkBuddy/薰儿/.scratch/aliyun-init.gradle`（**未提交、不属于项目改动**）。
- 固定参数：`--no-watch-fs --no-configuration-cache`（规避 transform 锁）。
- JDK：`C:/Program Files/Eclipse Adoptium/jdk-25.0.4.101-hotspot`；SDK：`D:/Android/Sdk`；`GRADLE_USER_HOME=D:/gradle-home`。

---

## 7. 回滚

- 第一轮（P0）无数据库迁移，回滚只需 `git checkout -- <改动文件>` 并删除新增的投影文件。
- 第二轮新增了 **v28 → v29**（`conversation_plans` 表）。回滚代码到旧版本时，旧 APK 的 DB 版本是 28，  
  Room 会认为数据库版本更高而拒绝打开；需要先清应用数据（或保留新 DB 并升级代码，不建议降级）。
- 因此第二轮之后，**回滚不再是纯代码操作**；发版前请确认保留 v29 的向前兼容。

---

## 8. 第二轮：真正的计划模式闭环（同日 19:20）

第一轮解决的是「清单本身正确」，第二轮补的是「计划这个交互姿态」：**先只读规划 → 提交审批 → 批准后执行**，对标 Codex Plan / Kimi EnterPlanMode-ExitPlanMode / DSH plan-mode。

### 8.1 状态机

```
NORMAL ──/plan 或 enter_plan_mode──▶ PLANNING
PLANNING ──exit_plan_mode──▶ REVIEW
REVIEW ──回复「批准」──▶ EXECUTING ──所有步骤结束──▶ NORMAL（自动收尾）
REVIEW ──回复「取消」──▶ NORMAL
REVIEW ──其他内容（修改意见）──▶ PLANNING
PLANNING / EXECUTING ──回复「取消」──▶ NORMAL
```

- `PLANNING` 与 `REVIEW` 是**只读阶段**：工具层硬拦截修改类工具，不只靠提示词。
- `REVIEW` 的批准会绑定 `approved_version`；版本不匹配时不能按"已批准"执行。
- 执行阶段所有步骤 terminal（完成/取消）后自动回到 `NORMAL`，避免下一轮还背着执行提示。

### 8.2 新增模块

| 模块                           | 职责                                                                 |
| ---------------------------- | ------------------------------------------------------------------ |
| `conversation_plans`（v29 迁移） | 会话级阶段、plan_id、version、approved_version、Markdown 正文、时间戳             |
| `AgentPlanPhase`             | NORMAL / PLANNING / REVIEW / EXECUTING + `isReadOnly`              |
| `AgentPlanModeStore`         | 阶段读写、enter/submit/approve/reopen/cancel、版本递增、批准绑定                  |
| `AgentPlanModeTool`          | `enter_plan_mode` / `exit_plan_mode` 两个闸门；提交时强制 steps 全 pending    |
| `AgentPlanModePolicy`        | 只读白名单：本地工具、MCP/动态工具统一拦截；`browser_use` 按 action 放行读取                |
| `AgentPlanModeRouter`        | `/plan`、`/plan off`、批准/取消/修改意见的文本路由；批准后把第一条 pending 置为 in_progress |
| `AgentPlanModeToolCatalog`   | 把 enter/exit 挂进工具目录，并继续与自动任务工具解耦                                   |
| `ToolIcons`                  | enter/exit 的专属图标，满足 `ToolCatalogUiTest` 的全工具图标契约                   |

### 8.3 关键行为

1. **`/plan <任务>`**：进入 PLANNING，清空旧清单；模型只读调研。
2. **`exit_plan_mode(plan, steps)`**：校验 steps（必须 pending、≤50 条、id 唯一）→ 写 todos → 计划版本 +1 → REVIEW；工具回执完整保留计划正文。
3. **用户回复「批准」**：绑定 approved_version，第一条 pending 自动变 in_progress，进入 EXECUTING，并把完整清单注入本轮请求。
4. **用户回复「取消」**：回到 NORMAL，清空 todos；计划正文保留可回看。
5. **用户回复其他内容**：视为修改意见，回到 PLANNING，提示模型更新后重新提交。
6. **待批准提示**：最后一次 `exit_plan_mode` 之后、用户尚未回复时，输入框上方显示「计划待批准 · 回复批准/取消/修改意见」，让文本审批入口可见；用户回复后自动消失。
7. **只读约束**：PLANNING/REVIEW 下 `write_file`、`terminal`、`todo_write`、`tap/type`、`memory_write`、`create_auto_task`、MCP 写工具等一律返回 `PLAN_MODE_READ_ONLY`；`read_file`、`observe_screen`、`web_search`、`memory_get` 等只读工具放行。
8. **自动任务隔离**：`maxRounds != null` 的自动任务 run 不挂计划工具、不注入清单、不参与阶段路由；离线 `SimpleAutoTaskRuntime` 也显式 `planToolsEnabled=false`，避免后台任务改写用户计划。
9. **状态转移**：已完成/已取消回退必须显式 `reopen=true`；`pending → completed` 放行但在回执里提示"跳过了 in_progress"。

### 8.4 与主流 Agent 的映射

| 能力         | 本项目                               | 对标                                                            |
| ---------- | --------------------------------- | ------------------------------------------------------------- |
| 进入计划模式     | `/plan`、`enter_plan_mode`         | Codex `/plan`；Kimi `EnterPlanMode`；DSH `/plan`                |
| 只读研究       | 白名单硬拦截（本地 + MCP）                  | Kimi 工具集硬限制；Codex/DSH 主要靠 policy                              |
| 提交审批       | `exit_plan_mode` + 文本「批准/取消/修改意见」 | Kimi `ExitPlanMode`；DSH `exit_plan_mode`；Codex approve/reject |
| 批准版本绑定     | `approved_version == version`     | DSH visual-plan 的 `approvedVersion`                           |
| 计划正文       | `conversation_plans.markdown`     | Kimi 计划文件；DSH `PLAN_v1.md`                                    |
| 执行清单       | `conversation_todos` + 进度条        | Codex `update_plan`；Claude `TodoWrite`                        |
| 子代理/自动任务隔离 | 自动任务不参与计划                         | Kimi 子代理独立 state；DSH 子代理只读评审                                  |

**取舍**：审批入口是文本而不是 UI Sheet。移动端聊天里用户可能随时离开，阻塞式弹窗容易丢状态；文本协议天然持久化在历史里，且 `/plan`、外部入口、自动任务可以复用。UI 快捷按钮可以后续加，协议不变。

### 8.5 测试与构建（第二轮）

- 计划模式相关 **101 例全部通过**：  
  `AgentPlanItemTest` 19、`AgentPlanToolTest` 18、`AgentPlanModeStoreTest` 9、`AgentPlanModeToolTest` 6、  
  `AgentPlanModePolicyTest` 6、`AgentPlanModeRouterTest` 13、`AgentPlanUiTest` 8、`AgentPlanContextProjectionTest` 3、  
  `AgentTraceFormatterPlanTest` 5、`AgentPromptBuilderTest` 6、`AgentPlanToolCatalogTest` 2、  
  `EtaDatabaseMigrationTest` 1、`ToolCatalogUiTest` 5。
- 迁移测试覆盖 **v6 → v29** 的完整迁移链，并逐列验证 `conversation_plans` 的列名/可空性，插入并读回一行。
- 全量单测：**1900 例 / 47 失败 / 2 跳过**。47 个失败全部是项目既有的 Windows 平台桶  
  （终端/PTY、路径、记忆文件锁、符号链接），**计划、迁移、提示词、工具目录相关 0 失败**。
- 顺手修了一个顺序依赖的测试前置：`AgentMemoryGetPagingTest` 原来只写约 12k 字符，  
  恰好等于默认页大小，共享记忆文件被清空时会误判"只有一页"；改为约 36k，全量复跑恢复稳定。
- 构建：
  - `Eta-v3.13.7-debug.apk`：114,046,981 字节（19:47）
  - `Eta-v3.13.7.apk`（release，R8）：30,653,435 字节（19:50）

### 8.6 手动验收建议

1. 新会话输入 `/plan 帮我调研一下这个 App 的通知权限怎么开`；
2. 模型应只读调研，然后调用 `exit_plan_mode`；工具卡展开可看到计划正文与"等待用户批准"；
3. 回复 `批准`：应进入执行，进度条出现，第一项为进行中；
4. 另一轮回复 `取消`：应回到普通对话，进度条消失；
5. 另一轮回复 `第二步改成先备份`：应回到计划模式继续完善；
6. 计划模式下让模型"改个文件/点一下"应收到 `PLAN_MODE_READ_ONLY`；
7. 执行完成后进度条隐藏，下一轮不再带执行提示。

---

## 9. 第三轮：把入口从斜杠命令改成界面（同日 20:05）

### 9.1 为什么改

前两轮的入口是 `/plan`。这在 CLI 里自然，但在手机上很别扭：

- 用户要记住一条命令，还要先按 `/` 才会看到候选；
- 审批也要手打「批准」两个字，而屏幕上已经有明确的"待批准"状态；
- 输入法、语音输入、外接助手这些入口根本打不出 `/plan`。

主流产品的做法都是**模式开关 + 审批按钮**（Codex 的 `/plan` 是终端产物，Claude Code 用 Shift+Tab，Kimi / DSH 在 TUI 里有明确的模式行）。移动端更应该有可见入口。

### 9.2 改成了什么

| 位置 | 形态 | 行为 |


|---|---|---|  
| 输入栏（附件按钮右侧） | `计划` pill | NORMAL → 进入规划；PLANNING / EXECUTING → 退出 |  
| 输入栏顶部 | 审批条（常驻） | REVIEW 阶段出现：`批准并执行` / `取消计划` |  
| 输入框占位文案 | 跟随阶段 | 规划中提示「描述任务，我先只读调研，再给你方案」 |  
| `/plan` | 保留 | 兼容外部入口、语音、键盘用户，行为不变 |

- **审批条不是弹窗**：移动端用户可能中途切走再回来，弹窗会消失并带走上下文；常驻条把"批准 / 取消 / 直接写修改意见"放在同一处。
- **审批按钮仍走文本协议**：点「批准并执行」等价于发出「批准」。这样版本绑定、清单起步、只读解除只有 `AgentPlanModeRouter` 一处实现，按钮路径无法绕过校验；历史里也留下一条可追溯的记录。
- **待批准时计划 pill 隐藏**：两个入口同时出现会给出两个"取消"，用户无法判断哪个会丢数据。

### 9.3 新增/改动

| 模块                       | 改动                                                                                                   |
| ------------------------ | ---------------------------------------------------------------------------------------------------- |
| `AgentChatUiState`       | 新增 `planPhase`（仅显示用；权威状态在库）                                                                          |
| `AgentPlanModeEntry`（新增） | 纯函数状态映射：点击会发生什么、审批条/pill 何时显示                                                                        |
| `AgentAppState`          | `togglePlanMode()` / `approvePlan()` / `cancelPlan()`；`refreshPlanPhase()` 在冷启动、切换会话、删除会话、run 收尾时重读库 |
| `AgentUiActions`         | 新增 `TogglePlanMode` / `ApprovePlan` / `CancelPlan`（Home 与 Chat 两套）                                   |
| `AgentChatInputBar`      | `PlanModeChip`、`PlanApprovalBar`、按阶段切换占位文案                                                           |
| `AgentChatBody`          | 删除旧的「回复「批准」开始执行」文本提示，改由审批条承担                                                                         |
| 字符串                      | 三种 locale 各 9 条（计划/规划中/待批准/执行中/审批标题与提示/按钮/占位）                                                        |

阶段同步点特意做成"多读一次库"而不是在 UI 里推算：模型可能在一轮里连续调用 `exit_plan_mode`、`todo_write`，只有库是唯一事实来源。

### 9.4 测试

- 新增 `AgentPlanModeEntryTest` **6 例**：四个阶段的 toggle 映射、REVIEW 不承担退出、审批条与 pill 互斥。
- `AgentPlanModeRouterTest` 新增 1 例：**按钮进入规划后普通消息保持 PLANNING 且不加前缀**（这条路径不经过 `/plan`）。
- 相关回归 **104 例全绿**（原 97 + 新增 7）。

---

## 10. 第四轮：补正确性缺口（同日 20:35）

第三轮解决了入口形态，这一轮补的是三个会真正改变行为结果的缺口。

### 10.1 计划正文进入上下文（原来只有步骤清单）

**问题**：`conversation_plans.markdown` 只在 `exit_plan_mode` 的当次工具回执里出现。上下文一旦压缩或被硬裁剪，模型就只剩步骤清单 —— 忘了目标、约束和验收标准，执行到一半开始跑偏，而进度条还显示"一切正常"。

**改动**：
- 新增 `AgentPlanTool.planContextText(markdown, items, maxPlanChars)`：正文 + 完整清单合并成一段注入文本，没有正文时自动退化为纯清单。
- run 开始注入（`planSnapshot`）用 1200 字预算；压缩摘要（`planOverview`）用 800 字，因为摘要本身要留预算。
- `AgentContextCompactor` 的前缀判断从 `startsWith("[当前任务清单")` 改为 `startsWith("[")`，否则会出现
  `[当前任务清单] [已批准计划 · 目标与约束]` 这种双层标题。
- 投影引导语从"当前任务清单"改为"当前计划与任务清单"。

### 10.2 执行期改清单要重新批准（批准不能被执行绕过）

**问题**：`EXECUTING` 下 `todo_write` 可以任意新增、删除、改写步骤。用户批准的是**一份具体清单**，执行时模型却能悄悄换成另一份 —— 批准形同虚设。

**改动**：
- `AgentPlanTool.execute(..., enforceApprovedScope: Boolean)`：执行阶段传入时，结构性变更（新增/删除/改写步骤）会让回执以固定前缀 `超出已批准范围：` 开头。
- 工具层识别该前缀后调用 `AgentPlanModeStore.reReviewBlocking()`：**版本 +1、`approved_version` 归零、phase → REVIEW**。清单照常落库（丢掉只会让两边更不一致），但下一次修改类工具已被只读策略拦住，必须等用户重新批准。
- **只推进 status 不算范围变更** —— 否则每完成一步都要重新批准，计划模式没法用。

**一个容易踩的坑**：模型没给 `id` 时 `create()` 会生成 `step-{index}` 位置 ID。删掉中间一条会让后面所有 id 平移，按 id 比较会把"删 1 条"误报成"重写 2 条"，把正常执行打断。所以范围比较用 **subject 多重集**，不用 id。测试 `执行期删掉中间步骤只报删除不误报改写` 专门锁这条。

系统提示的 EXECUTING 段也同步改成"改结构会被系统判定并退回待批准，不要假装仍在执行"。

### 10.3 审批条直接展示计划正文

**问题**：审批条只写"计划已提交，等待确认"。用户要读方案得往上翻、找到 `exit_plan_mode` 的工具卡再展开—— 真机上很难受。

**改动**：
- `AgentChatUiState.planPreview`：`refreshPlanPhase()` 一并读 `markdown`，只在 REVIEW 阶段填充。
- `PlanApprovalBar` 显示正文，默认 3 行、点击展开/收起；按钮下方保留"也可以直接输入修改意见"的提示。

### 10.4 角色会话不再挂载计划（顺手）

`planEligible` 增加 `roleplayContext == null`：角色对话里没有"只读规划 → 批准执行"这回事，`enter_plan_mode` / `todo_write` 不该出现。UI 侧给 `AgentChatInputBar` 加 `planAvailable`，角色会话直接隐藏 pill。

### 10.5 测试

| 用例 | 覆盖 |
|---|---|
| `执行期只推进状态不算超出批准范围` | 正常执行不被打断 |
| `执行期新增步骤会标记超出批准范围且清单照常落库` | 前缀 + 落库 |
| `执行期删掉中间步骤只报删除不误报改写` | id 漂移不误判 |
| `规划阶段改结构不会标记范围变更` | 规划期自由改 |
| `空清单对比不视为范围变更` | 首次建清单 |
| `计划正文与清单一起注入` / `没有正文时退化为纯清单` / `正文超预算时截断并标注` | 注入合并 |
| `执行期范围变化用当前正文重新走审批` / `没有正文时无法退回待批准` | 版本递增、批准失效 |

相关测试 **133 例全绿**（较上轮 +10）。

---

## 11. 修复：计划结束后仍每轮注入旧计划（20:48）

评审"是否生产级"时发现一个持续烧 token 的泄漏：

- 计划执行完成后 `shouldFinish → cancelBlocking`，phase 回到 NORMAL，但 `markdown` 与清单**保留供回看**；
- 而注入条件写的是 `planPhase != PLANNING` —— NORMAL 也满足，于是之后每一轮普通聊天都会白背最多 3200 字的旧计划正文+清单。

**修复**：把"是否注入、注入什么"抽成纯函数 `AgentPlanPhase.injectionScope(hasOpenWork)`：

| 阶段 | 注入 |
|---|---|
| REVIEW / EXECUTING | 计划正文 + 清单 |
| NORMAL | 仅当清单还有未完成项时，只注入清单 |
| PLANNING | 不注入 |

`hasPlan` 这个此前定义却从未被使用的属性，正好是 REVIEW/EXECUTING 的判定，一并接上。
新增 `AgentPlanModeStoreTest.注入范围随阶段与未完成项变化` 锁住边界。

