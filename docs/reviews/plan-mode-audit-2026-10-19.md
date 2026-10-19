# 计划模式审查报告（查漏补缺 + 对标改进）

- **审查对象**：`wzcawzc123/XunEr-agent`，commit `c6251d5`（v3.13.7）
- **审查范围**：`agent/plan/*`、`data/db/plan/*`、`AgentPlanToolCatalog`、`AgentRuntimeRunExecutor`、`AgentContextCompactor`、`AgentChatBody`、`AgentPlanProgressBar`、相关测试与发布说明
- **前提**：当前项目为唯一有效版本；`D:/kimi code` 下的旧副本不作为依据
- **结论口径**：区分「缺陷（bug）」「能力缺口（gap）」「可保留的取舍（trade-off）」，不把设计选择一律当 bug
- **实施状态（2026-10-19）**：5 个 P0、部分 P1/P2，以及 PR4「计划模式闭环」（阶段状态机 / 只读约束 / 提交审批 / 批准版本绑定）已落地，见 `docs/reviews/plan-mode-implementation-2026-10-19.md`；UI Room Flow 与遥测尚未开始

---

## 0. 结论摘要

### 0.1 做得好的地方（应保留）

1. **分层干净**：`AgentPlanItem`（纯模型/校验）→ `AgentPlanStore`（模型↔实体唯一转换点）→ `ConversationTodoDao`（Room）。没有 Android/JSON 依赖的纯 JVM 测试覆盖了数据层，这个结构比多数同类实现更可维护。
2. **全量覆盖语义选得对**：单线程 agent 下，`replaceAll` 先删后插（`ConversationTodoEntity.kt:65-69`）确实消灭了差量更新最常见的一类脏状态。
3. **校验集中且面向模型**：条数上限、id 唯一、至多一条 `in_progress`（`AgentPlanItem.kt:89-109`），错误文案是可直接读的中文，模型能自我修正。
4. **与压缩的耦合意识**：清单放独立表、不进 checkpoint（`EtaDatabase.kt:130-153`），并在摘要里带一行概览（`AgentContextCompactor.kt:118-122`）——方向正确，问题在"只带一行"（见 P0-3）。
5. **回执格式有回归锁**：`AgentPlanUiTest.kt:60-90` 直接走真实回执生成路径校验解析，能防止"改了措辞、进度条静默消失"。

### 0.2 核心判断

**当前实现是"任务清单 / 进度跟踪"，不是"计划模式"。**

它具备 Codex `update_plan`、Kimi `SetTodoList` 这一层的追踪能力，但缺少"计划模式"真正的核心：**执行前的只读研究阶段、计划产物的评审与批准、批准版本与执行版本的绑定**。这一点项目自己的发布说明也已承认（`docs/releases/v3.13.1.md:41`）。

在这个定位下，代码质量是合格的；但如果产品对外叫"计划模式"，就会持续出现预期落差。

### 0.3 问题分布

| 级别     | 数量 | 性质                     |
| ------ | -- | ---------------------- |
| **P0** | 5  | 正确性 / 用户可见的错误行为，建议立即修  |
| **P1** | 8  | 功能完整性、边界情况、中断恢复、状态管理缺口 |
| **P2** | 6  | 工程化、可观测性、长期演进          |

---

## 1. 现有实现拆解

### 1.1 数据流

```
模型
 │  todo_write(items[])  ← 唯一写入口，全量覆盖
 ▼
AgentPlanTool.execute()         协议层：JSON 解析 + 校验 + 生成文本回执
 │
 ├─ AgentPlanItem.create/validateAll   纯逻辑校验
 ├─ AgentPlanStore.replaceBlocking()   同步落库（工具线程）
 ▼
conversation_todos 表（conversation_id + item_id 主键，position 排序）
 │
 ├─ 压缩时：AgentPlanStore.loadBlocking() → overviewText() → 摘要正文一行
 └─ UI 时：不读库，改为解析历史里最后一条 todo_write 的 resultSummary
```

### 1.2 关键契约

| 契约   | 现状                                              | 评价                                      |
| ---- | ----------------------------------------------- | --------------------------------------- |
| 写入语义 | 全量覆盖，省略即删除，空数组即清空                               | 合理，但缺 diff/审计（P1-4）                     |
| 状态   | `pending / in_progress / completed / cancelled` | `cancelled` 是加分项；但进度口径有坑（P0-5）          |
| 校验   | ≤50 条、≤120 字、id 唯一、≤1 个进行中                      | 基本够；未知状态被静默降级（P0-2）                     |
| 回执   | 纯文本，含进度 + 当前项 + 全量列表                            | 模型可读；但无结构化 `ok`（P1-3）、全量回显费 token（P2-2） |
| 注入   | 仅压缩时注入一行概览                                      | 运行开始不注入（P0-4）、压缩后丢步骤（P0-3）              |
| UI   | 解析回执文本                                          | 脆弱、失败回执会吞掉进度条（P0-1、P1-2）                |
| 清理   | `AgentPlanStore.clear()` 存在                     | **零调用**（P1-5）                           |

---

## 2. 对标分析

### 2.1 OpenAI Codex CLI

**两层能力是分开的：**

- **Plan 协作模式**：`/plan` 或 Shift+Tab 进入，只读研究、最多约 4 轮澄清问题、产出结构化计划（steps / files / acceptance criteria），用户 approve / edit / reject 后才执行。注意：**约束只在提示词层**，官方也说明它不是沙箱，真正防写要靠 read-only sandbox。
- **`update_plan` 工具**：`explanation`（可选）+ `plan[]`，每项 `{step, status}`，状态只有 `pending / in_progress / completed`（**没有 cancelled**）。系统提示要求：步骤 1 句话、5-7 个词；恰好一个 `in_progress`；完成即标记，可一次标多条；全部完成后必须把所有步骤标为 `completed`；**调用后不要在正文复述计划**（TUI 已经展示）。
- **计划进入多个 prompt 槽**：base prompt、协作模式指令、多 agent 指令、**压缩摘要器**、goal-continuation、prewarm 模板。这是它"压缩后仍知道自己在干什么"的关键。
- **恢复**：较新版本 resume 会恢复 Plan 模式。
- **可关闭**：2026-08-31 起 `update_plan` 改为 opt-in，避免与 Linear/Jira 等外部计划系统"双写"。

**可直接借鉴**：状态机写进系统提示；压缩摘要器注入**结构化计划**（不是一行）；计划模式与追踪工具分层；提供开关，避免简单任务被工具诱导。

### 2.2 MoonshotAI Kimi CLI

- **`SetTodoList`**：每项 `title + status(pending/in_progress/done)`；**省略参数 = 查询当前列表，空数组 = 清空**；持久化在 `SessionState.todos`；子代理各自维护 `state.json`，**不会覆盖父代理的里程碑**；返回非空字符串确认，防止"command storm"（模型反复调同一个工具）。
- **Plan Mode**：`EnterPlanMode`（需用户同意）→ 只读研究 → 把计划写进指定的 Markdown 计划文件 → `ExitPlanMode`（**必须有计划文件 + 用户批准**）→ 进入执行。Plan 模式下工具集受限：只读工具 + **只允许写计划文件**，`TaskStop` 被完全拦截。
- **入口**：`/plan`、Shift+Tab、`kimi --plan`。
- **备选方案**：`ExitPlanMode` 可带 1–3 个备选方案（label ≤80 字符，禁用 Approve/Reject 等保留词），供用户审批时选择。

**可直接借鉴**：查询语义（省略=查询）、子代理隔离、计划文件、审批入口、备选方案、返回非空确认防抖。

### 2.3 DeepSeek Harness（dsh）

- **`@deepseek-ai/dsh-plan-mode`**：`/plan` 进入（可附带消息/图片/文件），`/plan off` 退出；agent 先探索和设计，再通过 **`exit_plan_mode`** 把完整计划交给你批准；**不限制工具**（官方明确说要用 sandbox + approval 强制限制）；**激活状态在会话恢复和 fork 后保留**；plan policy 文本由部署方配置。
- **dsh-plans 插件**：只读调研 → 一次一个问题 → 在 `./dsh-plans/` 产出带**稳定 ID、证据和 Verifier Checklist** 的 Markdown 计划 → 只读 reviewer/critic 子代理评审 → **显式交接问题（无自动完成）** → 作为持久 goal 执行，直到 checklist 全部通过；决策记录在 `DECISIONS.md`。
- **visual-plan 插件**：`Plan v1 → v2 …` **不可变版本**、**Plan Diff**、`approvedVersion` / `executionVersion`，**执行绑定批准版本**；执行中遇到变化**不得静默修改已批准计划**，必须先提新计划再批准。

**可直接借鉴**：批准版本绑定、版本不可变、Verifier Checklist、显式交接、状态跨 resume/fork 持久化。

### 2.4 Claude Code（参考）

- `TodoWrite`：`content / activeForm / status`，返回 **oldTodos → newTodos 的 diff**，聊天里不渲染（独立任务面板）。
- Plan mode：调研后用 `ExitPlanMode` 提交计划，需要用户批准。
- V2 `TaskCreate/TaskUpdate`：任务 ID、磁盘持久化（`.claude/tasks/`，带文件锁）、owner、`blocks/blockedBy` 依赖、metadata，为多代理准备。

**可直接借鉴**：回执返回 diff 而非全量；任务 ID 与依赖（未来多代理时）。

### 2.5 对比表

| 维度        | 本项目                  | Codex             | Kimi CLI                         | DSH                 | Claude Code                |
| --------- | -------------------- | ----------------- | -------------------------------- | ------------------- | -------------------------- |
| 追踪工具      | `todo_write`（1 个，全量） | `update_plan`     | `SetTodoList`                    | 计划文件 + goal         | `TodoWrite` / `TaskCreate` |
| 状态值       | 4（含 cancelled）       | 3                 | 3（done）                          | 计划阶段状态              | 3                          |
| 交互式进入计划模式 | **无**                | `/plan`、Shift+Tab | `EnterPlanMode`、`/plan`、`--plan` | `/plan`、`/plan off` | `ExitPlanMode` 流程          |
| 只读研究阶段    | **无**                | 有（提示词层）           | 有（工具集硬限制）                        | 有（policy，工具仍可用）     | 有                          |
| 计划评审/批准   | **无**                | 有                 | 有（计划文件 + 批准）                     | 有（`exit_plan_mode`） | 有                          |
| 批准版本绑定    | **无**                | 无（会话级）            | 计划文件                             | visual-plan 有       | 无                          |
| 压缩时注入计划   | 仅一行概览                | 完整计划进摘要器          | 每步 checkpoint                    | 计划文件/状态             | 会话状态                       |
| 跨重启/恢复    | 清单在库，但不注入模型          | resume 恢复模式       | SessionState                     | 跨 resume/fork       | 磁盘任务                       |
| 回执 diff   | 无                    | TUI 状态            | 无                                | Plan Diff（插件）       | 有（old→new）                 |
| 子代理隔离     | 参数未接线                | 计划进入多 agent 指令    | 独立 `state.json`                  | 只读评审子代理             | Task owner/依赖              |
| 取消语义      | 有 cancelled          | 无                 | 无                                | 计划可放弃               | 删除/拒绝                      |

---

## 3. 问题清单

### P0（正确性 / 用户可见错误）

#### P0-1 失败回执被判定为「成功」，且会让整张进度条消失

**现象**：`todo_write` 因参数错误被拒（例如 model 少给 `subject`）后，界面上的进度条直接消失；工具卡片仍显示成功样式。

**证据链**：

1. 校验失败返回纯文本 `"失败：第 2 条缺少 subject..."`（`AgentPlanTool.kt:116-130`）。
2. `AgentTraceFormatter.isSuccessResult()` 对非 JSON 内容一律返回 `true`（`AgentTraceFormatter.kt:262-263`），`summarizeResult()` 对 `todo_write` 原样返回文本（`:287`）。
3. UI 的 `latestPlanUi()` **只取最后一条** `todo_write` 消息（`AgentChatBody.kt:1258-1267`），而 `AgentPlanUi.parseFrom()` 只认 `"任务清单已更新"` / `"已清空任务清单"`（`AgentPlanUi.kt:35-46`）→ 返回 null → 进度条不渲染。
4. 但数据库里的旧清单仍在（失败写入不会落库）。

**影响**：一次可恢复的参数错误，被放大成"计划消失 + 状态误报成功"；用户与模型对当前进度的认知分叉。

**修法**：统一结果信封（见 P1-3）；UI 只接受 `ok=true` 的回执，或在解析失败时回退到上一条成功回执；最终形态是 UI 直接读库（见 5.1）。

---

#### P0-2 非法 `status` 被静默降级为 `pending`

**现象**：模型写 `"status": "done"`（或 `"完成"`）时，该条被当成 `pending` 落库，回执里却显示"待开始"；进度永远差一步。

**证据**：

- `AgentPlanStatus.fromWire()` 明确拒绝别名并返回 `null`（`AgentPlanStatus.kt:26-29`，测试也锁了这个行为：`AgentPlanItemTest.kt:25-32`）。
- 但 `AgentPlanItem.create()` 把 `null` 直接当默认值：`status = status ?: AgentPlanStatus.PENDING`（`AgentPlanItem.kt:76`）。
- 调用点也没有检查：`status = AgentPlanStatus.fromWire(raw.optString("status"))`（`AgentPlanTool.kt:124`）。

**影响**：这正是测试注释所说的"让模型的错误写法暴露出来"的反面——错误被吞掉，模型收到成功回执，不会自我修正。属于**契约与实现自相矛盾**。

**修法**：区分"缺省"与"非法"：

```kotlin
val raw = raw.optString("status").trim()
val status = if (raw.isEmpty()) AgentPlanStatus.PENDING else
    AgentPlanStatus.fromWire(raw)
        ?: return "失败：items[$index].status=\"$raw\" 不合法；可用值：pending / in_progress / completed / cancelled"
```

如确实想兼容别名，建一张显式别名表并在回执里提示"已按 completed 处理"，不要静默。

---

#### P0-3 压缩后只剩一行概览，模型丢失剩余步骤

**现象**：长会话压缩后，模型只记得"2/7 完成 · 进行中：X"，不再知道剩下 5 步分别是什么；表现为重写计划、重复已完成步骤，或直接问用户"接下来做什么"。

**证据**：

- 摘要只追加一行：`[当前任务清单] $planOverview`（`AgentContextCompactor.kt:118-122`）。
- `overviewText()` 的内容也只有 `done/total` + 当前项或"下一条"（`AgentPlanTool.kt:168-179`），**不包含完整清单**。

**影响**：这恰好击穿了该功能存在的理由——`docs/specs/2026-10-18-improvements.md:12-14` 写明"压缩会把回执摘要掉，模型随即失去我在第几步的记忆"。现在只解决了一半：知道第几步，但不知道后面几步。

**修法**：摘要里注入**完整清单的紧凑渲染**（见 5.1 的 `renderChecklist`），并注明"以本清单为准，历史回执可能已被摘要"。

---

#### P0-4 运行开始从不注入清单，数据库不是模型可见的权威状态

**现象**：同一会话里新开一轮（用户继续追问、重启 App、历史被硬裁剪/编辑）时，模型只能从历史消息里"碰运气"看到上次的 `todo_write` 回执；一旦回执被裁剪或消息被编辑掉，模型就不知道有清单，而 UI/数据库仍有。

**证据**：

- `planOverview` 只接到压缩路径（`AgentRuntimeRunExecutor.kt:268-279`），未参与请求组装。
- 启动时不读 `conversation_todos`；硬裁剪 `AgentHistoryTrimmer` 会丢最旧消息；用户编辑消息会截断后续历史。
- `todo_write` 落库发生在工具执行中，回执消息在之后才 append（`AgentPlanTool.kt:132` → `AgentLoop.kt:301`）；崩溃在两者之间会造成"库里有、历史里没有"。

**影响**：清单的持久化只对 UI 有意义，对模型不可靠；长任务跨轮次/跨重启的连续性没有保障。

**修法**：每次 run 组装请求时，从库读清单并作为**临时尾部块**注入（不写回历史、不影响前缀缓存）；同时保留压缩注入。注意与 Anthropic 签名回合的兼容：只在没有 pending tool response 时注入（见 5.1 的落地细节）。

---

#### P0-5 `cancelled` 被移出分母，可能以「100% 完成」假象收尾

**现象**：模型把做不动的步骤标成 `cancelled`，其余标 `completed` 后，进度条显示 `2/2` 并自动消失；回执只说"没有待开始或进行中的条目"，不提示有 1 条被取消。用户以为全部完成。

**证据**：

- `progressOf()` 明确把 `cancelled` 从分母剔除（`AgentPlanItem.kt:22-25`）。
- `buildResultText()` 在无 pending / in_progress 时输出"没有待开始或进行中的条目"（`AgentPlanTool.kt:148-153`），不区分"全完成"与"有取消"。
- UI `isActive = total > 0 && done < total`（`AgentPlanUi.kt:22`）→ 一旦 `done == total` 就直接不渲染（`AgentPlanProgressBar.kt:53`）。

**影响**：这是"完成度"语义的漏洞，模型可以用取消来"刷完成率"；用户失去对放弃项的知情权。

**修法**：

1. 回执显式分账：`已完成 2 / 取消 1 / 待开始 2`；有取消时追加"取消项不计入完成，请确认是否接受"。
2. UI 保留"已结束（2 完成 · 1 取消）"终态一段时间/直到下一轮，而不是瞬间消失；取消项用 `✕` + 删除线，不能和待开始一样是 `○`。
3. `overviewText` 同样带取消计数。

---

### P1（功能完整性 / 边界 / 恢复 / 状态管理）

#### P1-1 计划没有生命周期与归属，旧计划长期残留

- 表只有 `conversation_id`，没有 `plan_id / run_id / created_at / version / scope`（`ConversationTodoEntity.kt:21-34`）。
- 一个会话里做第二件不相干的事，上一件未完成的清单仍会显示；模型也没有"这是旧任务"的信号。
- 自动任务投递到同一会话（`AutoTaskDispatcher` 的 `conversationId`）时，后台 run 可以重写甚至清空用户当前的计划；Kimi 对子代理是**独立 state.json**，本项目没有隔离。

**建议**：引入 `plan_id + scope(chat/autotask/roleplay) + created_at + version`；新用户任务开始时，若旧计划 `active` 且多轮未更新，标记 `abandoned` 或提示模型收尾；自动任务 run 只读或不共享计划命名空间。

#### P1-2 UI 依赖文本解析，且用中文 label 当状态键

- 正则解析回执（`AgentPlanUi.kt:26-28`）：`subject` 内含换行会解析丢行；回执被截断会少行但进度仍显示总数。
- `AgentPlanProgressBar` 用 `item.label`（"已完成/进行中"）做 `when` 分支（`:135-139`），文案一改图标就静默错。
- `cancelled` 落到 `else` 分支显示成 `○`，与待开始无法区分（同上）。
- 失败回执直接让进度条消失（P0-1）。

**建议**：UI 改为观察 Room `Flow`（见 5.1），`Item` 携带 `status: AgentPlanStatus` 而不是 label；文本解析仅保留为降级路径。

#### P1-3 结果没有统一结构，成功/失败不可程序判定

- 成功是纯文本，校验失败是 `"失败：..."` 纯文本，异常路径才是 `{"ok":false,...}` JSON（`AgentLocalTools.kt:530-539`）。
- `isSuccessResult`、诊断导出、UI 状态都按 JSON 处理，`todo_write` 全部落空（P0-1 的根因之一）。

**建议**：所有工具统一 `{"ok":bool,"code":...,"message":...,"data":...}`；`todo_write` 的 `data` 带 `progress/items/diff`，另给 `display` 一行摘要供 UI 卡片使用。

#### P1-4 状态机缺失：允许回退、跳步、复活，且没有 diff/审计

- 只校验"≤1 个 in_progress + id 唯一"（`AgentPlanItem.kt:89-109`）。
- 允许 `completed → in_progress` 回退、`pending → completed` 跳步、`cancelled → pending` 复活；没有原因记录。
- 全量覆盖 + 无 diff：模型少写一条已完成项，等于静默删除，用户和后续统计都看不到"曾经做完过什么"。


**建议**：

- 首次写入自由；**后续更新**按旧清单校验转移：`pending→in_progress→completed`、`pending/in_progress→cancelled` 合法；`completed→in_progress`、`cancelled→pending` 需要显式 `reopen=true`；`pending→completed` 默认拒绝（或返回警告，Codex 的提示词就是禁止跳步）。
- 回执返回 `added/removed/updated` 三项计数与变更条目（Claude 的 old→new diff 思路）。
- 可选：`plan_events` 表记录每次版本的 diff，供审计/回放。

#### P1-5 会话删除/备份恢复不清理、不迁移清单

- `AgentPlanStore.clear()` 有实现但**零调用**（`AgentPlanStore.kt:36-39`）。
- 会话删除走 `ConversationDao.replaceAll`（`ConversationDao.kt:296-310`），只删 conversations/messages/checkpoints/state，**不碰 conversation_todos**；表也没有外键级联（`EtaDatabase.kt:137-153`）。
- 备份文档结构里没有 todos（`EtaBackupRepository.kt:26-40`），恢复后旧行残留、新计划丢失。

**建议**：删除会话与恢复备份时调用 `clear`；或在建表时加 `FOREIGN KEY(conversation_id) REFERENCES conversations(id) ON DELETE CASCADE`（需重建表）；启动时做一次孤儿清理（`DELETE FROM conversation_todos WHERE conversation_id NOT IN (SELECT id FROM conversations)`）。

#### P1-6 无会话标识的入口仍挂载 `todo_write`

- `conversationId` 只从 UI handoff 推导（`AgentRuntimeRunExecutor.kt:107-111`）；语音/外部助手入口可能为空。
- 工具仍会出现在工具表里（`AgentToolCatalog.kt:41` 无条件挂载），模型认真调一次只会拿到"当前会话尚无标识，无法保存"（`AgentPlanTool.kt:105-107`），白费一轮。

**建议**：挂载时传 `writable = conversationId != null`；或给无会话入口一个 run 级临时命名空间（`runId`），至少让清单在本轮有效。

#### P1-7 `writable` 参数是死代码，子代理隔离未接线

- `AgentPlanToolCatalog.appendTo(writable: Boolean = true)` 的注释写明"只读运行不应改写主任务清单"（`:17`），但调用点从未传 `false`（`AgentToolCatalog.kt:41`），当前也没有子代理实现。
- 一旦引入子代理，默认会共享主会话清单，重演 Kimi 明确避免的问题。

**建议**：现在就把 `writable` 接到"是否主代理/是否 UI 会话"；未来子代理用独立 `plan scope`。

#### P1-8 中断/崩溃窗口无对账，续跑提示不含清单

- 取消/服务被回收后，`in_progress` 项永远停在 `in_progress`，没有 `interrupted` 语义。
- `RESUME_INTERRUPTED_PROMPT` 只说"基于历史继续"，没有说"先读当前清单、从进行中项继续"（`AgentAppState.kt:2296-2298`）。
- 崩溃在"落库成功、回执未入历史"之间时，库与历史不一致，UI 不显示但模型也不知道（与 P0-4 同源）。

**建议**：取消/失败时把 `in_progress` 回退为 `pending` 并记录 `interrupted_at`（或新增 `interrupted` 状态）；续跑提示注入清单快照；run 开始时以库为准做一次对账。

#### P1-9 没有"计划是否被遵守"的闭环

- 没有"连续 N 轮未更新清单"的提醒；模型可以写完计划就再也不看。
- `completed` 的语义没有定义成"已验证通过"，工具描述只说"做完置为 completed"（`AgentPlanTool.kt:28-33`）。
- Codex / Claude Code 都有"完成 3+ 步但没有验证步骤就提示验证"的机制；DSH 直接要求 Verifier Checklist。

**建议**：至少加一条轻量提醒（计划 active 且 5 轮未更新 → 尾部提示"如已完成请更新清单，如已放弃请取消或清空"）；在工具描述里明确 `completed` 仅在验证通过后使用；长期引入可选的 `verify` 步骤类型。

---

### P2（工程化 / 可观测 / 长期）

| 编号    | 问题                                                                                   | 证据                                                        | 建议                                                                         |
| ----- | ------------------------------------------------------------------------------------ | --------------------------------------------------------- | -------------------------------------------------------------------------- |
| P2-1  | `MIN_ITEMS_FOR_PLAN = 3` 是死常量，"3 步以上才用"只写在描述里                                        | `AgentPlanItem.kt:33`；无引用                                 | 要么校验/提示，要么删除；建议保留为提示词约束，不在代码里强制                                            |
| P2-2  | 成功回执每次回显全量清单，token 随轮次线性增长；Codex 只回 ack，Claude 回 diff                                | `AgentPlanTool.kt:155-158`                                | 回执改为"一行进度 + 变更项"，完整清单交给 UI/库                                               |
| P2-3  | 回执 >2048 字符会被 `AgentToolResultPruner` 头尾截断，模型看到的清单可能不完整                              | `AgentToolResultPruner.kt:32-41`                          | 对 `todo_write` 设白名单不修剪，或改用结构化重注入                                           |
| P2-4  | 缺省 `id=step-N`，重排后身份漂移，无法做跨版本 diff/统计                                                | `AgentPlanItem.kt:61`                                     | 默认用 `subject` 的稳定 slug/hash，或强制模型给 id                                      |
| P2-5  | 无 per-item 时间戳、证据、验收标准、依赖                                                            | 实体只有全表统一的 `updatedAt`（`ConversationTodoEntity.kt:33`）     | 长期加 `created_at/started_at/finished_at/note/acceptance`；多代理时加 `depends_on` |
| P2-6  | 无计划版本/历史/导出；备份不含清单                                                                   | `EtaBackupRepository.kt:26-40`                            | 中期加 `conversation_plans`（version/approved_version/markdown）；备份补表           |
| P2-7  | 测试缺口：无 Room 事务测试、无压缩/重启/并发/失败回执用例；FakeDao 覆写了真实 `@Transaction` 默认方法                  | `AgentPlanToolTest.kt:21-37`                              | 补 Robolectric/Room in-memory 测试（见第 6 节）                                    |
| P2-8  | 系统提示与计划模式冲突：`不要先输出计划`，且全文从未提到 `todo_write`                                           | `AgentPromptBuilder.kt:74`；grep 无 `todo_write`            | 改为"不要在正文长篇罗列计划；多步任务用 todo_write 记录"；加一段静态使用规范（可缓存）                         |
| P2-9  | 无遥测：无法统计创建率、完成率、放弃率、非法状态率、计划漂移率                                                      | 无相关事件                                                     | 加 `plan_created/updated/cleared/invalid/stale/resumed` 事件                  |
| P2-10 | `AgentPlanStore.load/replace` 生产代码未使用；`AgentPlanToolCatalog` 同时挂 AutoTask 工具，命名与职责不符 | `AgentPlanStore.kt:18-33`；`AgentPlanToolCatalog.kt:20-47` | 清理死 API；拆分 AutoTask 目录                                                     |

---

## 4. 与外部实现的逐项差距（归纳）

### 4.1 功能完整性

| 缺口     | 本项目         | 外部做法                                                           |
| ------ | ----------- | -------------------------------------------------------------- |
| 只读研究阶段 | 无           | Codex/Kimi/DSH 都有；Kimi 是工具集硬限制，Codex 是提示词层                     |
| 计划产物   | 内存/DB 的条目列表 | Kimi 写 Markdown 计划文件；DSH 有 `PLAN_v1.md` + `DECISIONS.md`       |
| 评审/批准  | 无           | Codex approve/edit/reject；Kimi ExitPlanMode；DSH exit_plan_mode |
| 版本与回滚  | 无           | DSH visual-plan 不可变版本 + Plan Diff + approvedVersion            |
| 备选方案   | 无           | Kimi ExitPlanMode 支持 1–3 个选项                                   |
| 备选/依赖  | 无           | Claude TaskCreate V2 `blocks/blockedBy`；DSH Verifier Checklist |
| 查询语义   | 无（必须全量写）    | Kimi 省略参数 = 查询                                                 |
| 子代理隔离  | 参数未接线       | Kimi 独立 state.json；Claude owner/依赖                             |

### 4.2 边界情况

- **未知状态**：本项目静默 pending；Codex/Kimi 用严格枚举/提示词约束。
- **取消语义**：本项目有 cancelled 但进度口径会掩盖放弃；Codex/Kimi 没有 cancelled（也就没有这个问题）。
- **空清单**：本项目空数组=清空，没有二次确认；Kimi 同样，但返回确认文本。
- **超长清单**：本项目 50 条上限，但回执会被 pruner 截断；Codex 建议 5-7 词短步骤，从源头控制。
- **无会话入口**：本项目工具仍挂载；Kimi 子代理独立存储。

### 4.3 异常与中断恢复

- **压缩**：本项目只注入一行；Codex 把完整计划注入摘要器/continuation prompt；DSH 靠计划文件。
- **进程死亡**：本项目清单在库但模型不可见；Kimi/DSH 状态跨 resume/fork；Codex 恢复模式。
- **中断续跑**：本项目提示不含清单；外部实现把计划作为续跑上下文的一部分。
- **崩溃对账**：本项目无；建议以库为准在 run 开始时对账。

### 4.4 状态管理

- **单写者假设**：本项目成立，但自动任务打破了"同一会话只有用户一个写者"。
- **转移合法性**：本项目只约束"至多一个进行中"；Codex 提示词有严格状态机；建议代码层补。
- **可追溯**：本项目无历史；DSH 有不可变版本；Claude 有 diff。

---

## 5. 改进方案

### 5.1 阶段一：P0 修复（建议 1–2 天，低风险，先落地）

#### ① 统一结果信封 + 失败判定

```kotlin
// 成功
{
  "ok": true,
  "data": {
    "progress": { "done": 2, "total": 5, "cancelled": 1 },
    "running": "写初稿",
    "items": [ { "id": "step-1", "subject": "...", "status": "completed" } ],
    "diff": { "added": 0, "removed": 1, "updated": 2 }
  },
  "display": "清单更新 2/5 · 进行中：写初稿 · 1 项已取消"
}
// 失败
{ "ok": false, "code": "PLAN_INVALID_STATUS", "message": "items[1].status=\"done\" 不合法；可用值：..." }
```

- `AgentTraceFormatter.isSuccessResult` 对 `todo_write` 读 `ok`；`summarizeResult` 用 `display`，不再回显全量列表。
- UI 卡片和进度条不再依赖 `"任务清单已更新"` 字面量。

**验收**：构造一次失败写入，进度条保持不变、工具卡显示失败；单测覆盖 `ok=false`。

#### ② 严格状态校验

按 P0-2 的代码片段改 `AgentPlanItem.create` 的调用点；补测试：

- `status="done"` → 整批拒绝，回执包含允许值；
- `status` 缺省 → pending；
- `status="COMPLETED"` → 通过（现有大小写宽容保留）。

#### ③ 压缩注入完整清单

新增渲染函数（放在 `AgentPlanTool`）：

```kotlin
fun checklistText(items: List<AgentPlanItem>, maxChars: Int = 2_000): String? {
    if (items.isEmpty()) return null
    val (done, total) = progressOf(items)
    val cancelled = items.count { it.status == CANCELLED }
    return buildString {
        append("[当前任务清单（权威状态，来自数据库；与历史冲突时以此为准）]\n")
        append("进度：$done/$total 完成")
        if (cancelled > 0) append("，$cancelled 项已取消")
        append("\n")
        items.take(30).forEachIndexed { i, it ->
            append("${i + 1}. [${it.status.wire}] ${it.subject}\n")
        }
        if (items.size > 30) append("…（共 ${items.size} 项，仅显示前 30 项）\n")
        append("请从 in_progress 项继续；不要重复 completed 项。")
    }.take(maxChars)
}
```

`AgentContextCompactor` 用它替换 `planOverview` 一行（保留 `overviewText` 给 UI 摘要用）。

**验收**：压缩后摘要文本包含全部（或前 30）步骤与状态；模型在压缩后能说出剩余步骤。

#### ④ 运行开始注入清单快照

- `AgentRuntimeRunExecutor` 增加 `planSnapshot` provider（读库 → `checklistText`）。
- 在 `AgentModelClient.complete` 组装完 `messages` 后、发请求前，若 `planSnapshot != null` 且 `!AnthropicEphemeralState.hasPendingToolResponse(messages)`，把块追加为一条**临时** user message（不写回 transcript/DB）。
- 注入位置放在最后一条 user 消息之后（或附加到该消息的文本块），保证不影响系统提示前缀缓存。
- 若历史里最后一条 `todo_write` 回执就在最近 N 条内，可跳过，避免重复。

**验收**：杀掉进程重启后继续同一会话，模型能复述清单；硬裁剪掉回执后仍能复述。

#### ⑤ 取消语义

- 回执分账 + UI 终态展示（见 P0-5）。
- `overviewText` 带 `cancelled` 计数。
- 进度条 `isActive` 改为"存在 pending/in_progress"；若全部 terminal 且有 cancelled，渲染一条"已结束（X 完成 · Y 取消）"的终态行，持续到下一轮用户消息。

**验收**：2 完成 + 1 取消时，进度条不显示 `2/2`，而显示 `2 完成 · 1 取消`；回执明确提示。

---

### 5.2 阶段二：补全"计划模式"（建议 3–6 天）

如果产品继续使用"计划模式"这个名字，建议补齐以下最小闭环（否则建议在文案上改回"任务清单/进度跟踪"）。

#### ① 状态机

```
NORMAL ──/plan 或 enter_plan_mode──▶ PLANNING
PLANNING ──exit_plan_mode──▶ REVIEW
REVIEW ──批准──▶ EXECUTING ──完成/放弃──▶ NORMAL
REVIEW ──修改意见──▶ PLANNING
REVIEW ──取消──▶ NORMAL
```

- 状态持久化（`conversation_plans.phase`），进程重启/续跑恢复（Codex/DSH 都这么做）。
- `enter_plan_mode` 需要用户确认（Kimi 的做法）；`/plan` 是直接入口。

#### ② 只读约束（双层）

- **提示词层**：plan policy 段落（可参考 DSH 的 `section` 配置）。
- **运行时层**：在 `beforeToolExecution` 里按 `phase == PLANNING` 拒绝写操作/设备动作，只放行只读工具与 `write_plan`；这比 Codex 的"提示词层"更可靠，也符合本项目的安全习惯（`AgentToolRequirements` 已有类似机制）。
- 工具集投影：`AgentToolCatalog.build` 增加 `planPhase` 参数，PLANNING 时不挂载写工具（双保险）。

#### ③ 计划产物与审批 UI

- 新表 `conversation_plans`：`plan_id / conversation_id / version / phase / markdown / approved_version / created_at / updated_at`。
- `exit_plan_mode` 返回 Markdown + 结构化步骤；UI 用底部 Sheet 展示，按钮：**批准并执行 / 修改意见 / 取消**。
- 批准后：`approved_version = version`，`phase = EXECUTING`，同时把步骤写入 `conversation_todos`（复用现有进度条）。
- 执行中模型想改已批准步骤：必须 `update_plan(change_reason=...)` 生成新版本并重新审批（DSH visual-plan 的规则）；只允许"追加不影响已批准范围的步骤"或"标记完成"，不允许静默删改。

#### ④ 自动任务/子代理隔离

- `AgentLocalTools` 增加 `planScope`（`chat` / `autotask` / `subagent`）；非 `chat` 只读或独立 `plan_id`。
- 自动任务 run 不允许清空主计划；如需跟踪，用自己的 `plan_id`。

#### ⑤ 恢复

- `AgentRunCheckpointStore` 或 `conversation_plans` 记录 phase；重启后恢复 `PLANNING/REVIEW`，不要静默退回 NORMAL。
- 续跑提示加一句："先读取当前任务清单，从 in_progress 继续；不要重复 completed。"

---

### 5.3 阶段三：结构化与可观测（长期）

1. **Step schema v2**：`id`（稳定）、`subject`、`activeForm`、`status`、`acceptance`、`evidence`、`dependsOn`、`startedAt/finishedAt`、`note`。
2. **计划事件日志**：`plan_events(plan_id, version, event, payload, at)`，支持回放/审计/统计。
3. **验证闭环**：`completed` 仅代表"验证通过"；连续 3+ 步没有验证动作时注入提醒（Codex/Claude 的做法）；可选 Verifier Checklist（DSH）。
4. **指标**：计划创建率（多步任务中）、完成率、取消率、非法状态率、漂移率（执行中新增/删除步骤数）、压缩后恢复成功率。
5. **UI**：计划历史版本、Plan Diff、导出 Markdown、手动取消/重开步骤。

---

### 5.4 不建议做的事

- **不要把 `todo_write` 拆成 Create/Get/Update/List 四件套**：单线程 agent 下这是负收益；Claude 的四件套是为并发子代理准备的。保持一个全量覆盖工具，补 diff 即可。
- **不要为了对齐 Codex 去掉 `cancelled`**：显式放弃比"悄悄省略"更诚实；要修的是进度口径，不是状态数量。
- **不要在所有简单任务上强制用计划**：Codex 已经把 `update_plan` 改成 opt-in；建议保留"3 步以上/用户明确要求"的约束，并考虑加开关。
- **不要让 UI 继续解析自然语言回执**：这是当前最脆弱的一环，越晚改成本越高。

---

## 6. 测试补充清单

### 6.1 单元测试（纯 JVM）

| 用例         | 断言                                                                       |
| ---------- | ------------------------------------------------------------------------ |
| 非法 status  | 整批拒绝、回执含允许值、库不变                                                          |
| 缺省 status  | pending                                                                  |
| 失败回执       | `isSuccessResult=false`；UI 不吞掉旧计划                                        |
| 取消语义       | `progressOf` 返回 cancelled 数；回执分账；`isActive` 正确                           |
| 状态转移       | completed→in_progress 拒绝；cancelled→pending 需 reopen；pending→completed 拒绝 |
| diff       | 少一条已完成项时 `removed=1` 且有提示                                                |
| 大清单        | 50 条回执长度、checklist 截断行为                                                  |
| 多行 subject | UI 解析不丢行（若保留文本解析）                                                        |

### 6.2 Room / Robolectric

- `replaceAll` 真的是事务（中途失败不留半张表）。
- 删除会话后 `conversation_todos` 为空（或外键级联）。
- 迁移 22→28 后表存在、索引存在、旧数据可读。
- `Flow` 观察能收到写入更新。

### 6.3 集成 / 回归

- 压缩前有清单 → 压缩后摘要含完整清单 → 模型能复述剩余步骤。
- 杀进程重启 → 新 run 注入清单 → 模型从 in_progress 继续。
- 硬裁剪掉最后一条回执 → run 开始仍注入清单。
- 自动任务 run 不修改主计划。
- 无 conversationId 的入口不挂载 `todo_write`。

---

## 7. 建议的 PR 拆分

| PR      | 内容                               | 风险 | 验收                  |
| ------- | -------------------------------- | -- | ------------------- |
| **PR1** | 结果信封 + 严格状态 + 失败判定 + UI 不被失败回执覆盖 | 低  | P0-1/P0-2 测试通过      |
| **PR2** | 压缩注入完整清单 + run 开始注入 + 取消语义       | 中  | P0-3/P0-4/P0-5 集成测试 |
| **PR3** | UI 改 Room Flow + 生命周期/清理/备份      | 中  | P1-1/P1-2/P1-5      |
| **PR4** | 计划模式状态机 + 审批 + 只读约束 + 版本绑定       | 高  | 手动验收 + E2E          |
| **PR5** | Step v2 + 事件日志 + 指标 + 验证闭环       | 中  | 指标可查                |

---

## 8. 参考来源

- OpenAI Codex Prompting Guide（`update_plan` schema 与 Plan tool 指引）：<https://cookbook.openai.com/examples/gpt-5/gpt-5-1-codex-max_prompting_guide>
- Codex 系统提示（update_plan 使用规范）：<https://github.com/nasrat-v/system_prompts_leaks/blob/main/OpenAI/Codex/gpt-5.md>
- Codex Plan 模式实践（只读、澄清、批准流程）：<https://codex.danielvaughan.com/2026/03/27/planning-mode-in-practice>
- Codex `update_plan` 改为 opt-in（与外部计划系统冲突）：<https://codex.danielvaughan.com/2026/08/31/codex-cli-update-plan-tool-opt-in-external-planning-competing-surfaces>
- The Annotated Coding Agent（Codex/Claude 计划与状态机对比）：<https://nima.sadri.ai/random/annotated-agent/>
- Kimi CLI Planning & Meta-Cognitive Tools（SetTodoList、Plan Mode、子代理隔离）：<https://deepwiki.com/MoonshotAI/kimi-cli/6.5-mcp-tools-and-external-integration>
- Kimi CLI Plan Mode（EnterPlanMode/ExitPlanMode、受限工具集）：<https://deepwiki.com/MoonshotAI/kimi-cli/8.3-external-services>
- Kimi Code CLI 内置工具（TodoList 省略=查询、空数组=清空；ExitPlanMode 备选方案）：<https://www.kimi.com/code/docs/kimi-code-cli/reference/tools.html>
- DeepSeek Harness `@deepseek-ai/dsh-plan-mode`（`/plan`、`exit_plan_mode`、跨 resume/fork）：<https://github.com/deepseek-ai/deepseek-harness/blob/c36a83ff6bb95e3f82cf79f9be7c724270a8aa61/packages/plan/plan-mode/README.zh.md>
- dsh-plans（只读规划、Verifier Checklist、显式交接）：<https://deepseekharness.io/zh/plugins/dsh-plans>
- DSH visual-plan（Plan Diff、不可变版本、approvedVersion）：<https://deepseekplugin.org/en/plugins/jiaqi23333-dsh-visual-plan>
