# 全局审查报告：短期记忆起步 → v3.13.8

> 范围：`c6b49b9`（记忆升级四机制：短期分层/rollup/记忆中心/热力图）→ `e17329a`（v3.13.8）
> 规模：**100 个提交 / 131 个文件 / +12,494 −96**
> 方法：四路并行只读审查（记忆页 / 计划模式 / 自动任务 / 数据层与测试），
> 关键结论由本人二次复核（标注 ✅ 已复核）。

---

## 0. 一句话结论

**功能面铺得很快，但「闭环」普遍缺失**：三个模块（自动任务、按模型统计、计划模式 REVIEW 退出）
都有「看起来做了、实际不可达」的功能；数据层有两条会静默丢数据的路径；
而测试恰好都覆盖在「能跑通的那一半」上，导致这些问题全绿。

---

## 1. P0：功能不可达 / 数据丢失 / 约束可绕过

### P0-1 「按模型」用量卡片永远不显示 ✅已复核

| 项 | 证据 |
|---|---|
| 页面只取总账 | `AgentUsageScreen.kt:74` → `AgentCostRepository(...).all()` |
| `all()` 不填 byModel | `AgentCostRepository.kt:25-29`，走 `toSummary()`，`byModel` 默认 `emptyList()` |
| `byModel()` 无人调用 | 全仓 `grep "\.byModel()"` = **0** |
| 渲染条件恒假 | `AgentUsageScreen.kt:128` `if (summary.byModel.isNotEmpty())` |
| 测试为何绿 | `AgentCostSummaryTest.kt:100-119` 手工 `listOf(ByModel(...))` 构造后断言数据类，**从不经过 SQL** |

v3.13.4「按模型分组」发布至今，这个卡片一次都没显示过。同一次提交同时加了查询和 UI，但漏改 `load()`。

**修复**：`all()` 改调 `byModel()`；测试补一条「Repository → 假 DAO → 断言分组」的用例。

### P0-2 REVIEW 阶段 chip / 菜单的退出按钮点了没反应 ✅已复核（我今天引入）

- chip 显示条件：`AgentChatInputBar.kt:205` `planPhase != AgentPlanPhase.NORMAL`
- 点击走向：`onTogglePlanMode` → `toggleAction(REVIEW) = **NONE**`（`AgentPlanModeEntry.kt:30-35`）
- 加号菜单同样显示「退出计划模式」，同样走 `onTogglePlanMode` → 同样无反应
- `showsToggle()`（本应在此处返回 false）是**死代码**，只被测试引用（`AgentPlanModeEntryTest.kt:61-64`）

用户在待批准阶段看到 chip 上有 ×，点了没动静，真正能取消的按钮在另一条审批条上。

**修复**：visible 条件改用 `showsToggle(planPhase)`；加号菜单在 REVIEW 时禁用该项或改文案。

### P0-3 点「整理」会静默丢弃未保存草稿

| 项 | 证据 |
|---|---|
| 按钮无脏检查 | `AgentMemoryScreen.kt:284-306`，`clickable(enabled = !busy)`，`busy = isLoading || isSaving` |
| 对比 | `:147-154` 的「切换目标」**有**脏检查 + 确认弹窗 |
| 强制刷新 | `AgentMemoryStore.kt:148` rollup 结束无条件 `refreshMemory()` |
| 覆盖草稿 | `AgentMemoryStore.kt:53-61` 成功后 `draft = snapshot.content; dirty = false` |

**修复**：加与切换目标一致的确认；`refreshMemory()` 在 dirty 时不清 draft。

### P0-4 备份/恢复会静默清空计划与清单；任务表既不备份也不清理

- 导出字段全集见 `EtaBackupRepository.kt:31-45`：**不含** `conversation_todos` / `conversation_plans` / `agent_tasks`
- 恢复时显式删除：`EtaBackupRepository.kt:162-164` `deleteAllConversationTodos()/Plans()`
  → **导出一次再导入一次，所有会话的任务清单和计划正文消失**，导入前无任何提示
- `agent_tasks` 恢复时**完全不动**（该文件零次 `agentTaskDao`）→ 任务继续指向已删除会话
- 缺节的备份文件静默为空：`EtaBackupJsonStreams.kt:133-135` `if (!file.exists()) return`

**修复**：三张表纳入备份（bump schemaVersion）；恢复前给摘要字段「将丢失 N 条」；`agent_tasks` 要么导出要么恢复时清空并提示。

### P0-5 自动任务在非 root 设备上根本不触发

- 唯一触发入口 `AutoTaskService.tryRunOnce()`，只能被外部 root 脚本 `am startservice` 唤醒；**脚本不在仓库**（`docs/releases/v3.13.5.md:48` 自认）
- 广播触发层 `AutoTaskTrigger` 整类零引用、未注册进 manifest
- 无 AlarmManager / WorkManager / BOOT_COMPLETED / onResume 兜底
- 发布说明里「未 root 可走 App 在前台时的投递路径」**这条路径不存在**

**修复**：加 App 内调度源（AlarmManager + resume + 充电/网络广播），root 脚本降级为加速器。

### P0-6 计划模式「只读」可被参数绕过

| 路径 | 证据 |
|---|---|
| `androguard_analyze` 透传 CLI 参数 | 白名单 `AgentPlanModePolicy.kt:39`；`AndroguardCommand.kt:43-49` 只做字符过滤，允许 `-o /path` 写文件 |
| `browser_use(navigate)` 可跑 `javascript:` | `AgentPlanModePolicy.kt:57-59` 放行；`AgentBrowserSession.kt:401` `loadUrl(rawUrl)` 无 scheme 校验 |
| `fetch_url` 任意 GET | 白名单 `:32`；可打内网管理接口（`?action=reboot` 类） |

**修复**：`navigate` 只允许 http/https；`androguard` 的 `extraArgs` 改选项白名单；`fetch_url` 主机黑名单或至少在文案里不承诺「不能改任何东西」。

### P0-7 批准/取消是前缀匹配，修改意见会被当成批准

- `AgentPlanModeRouter.kt:22-27` 的 `APPROVAL_PREFIXES` 含 `"执行"`、`"开始执行"`；判定 `length <= 12 && startsWith`（`:145-146`）
- 触发：REVIEW 输入「执行前先确认备份」（8 字）→ **直接进入 EXECUTING 并开始改设备**
- 反向：PLANNING/EXECUTING 输入「取消按钮的颜色改成红色」→ **直接放弃计划**

**修复**：批准改精确匹配（或只保留 `/approve` 显式命令）；取消同理。

---

## 2. P1：设计缺陷（会导致行为不可预期）

| # | 问题 | 关键证据 |
|---|---|---|
| P1-1 | 自动任务有两套执行引擎：投递走 runtime、离线走 `complete()`。模型/工具开关/能力/记忆/技能/压缩五处不一致；用户关掉的工具在后台被重新打开 | `SimpleAutoTaskRuntime.kt:244-322` vs `AgentRuntimeRunExecutor.kt:120-405` |
| P1-2 | 离线执行的结果只写消息表，**不回写 checkpoint** → 模型下一轮看不到自己上次的结果，UI 与模型上下文永久分裂 | `SimpleAutoTaskRuntime.kt:370-395` vs `AgentConversationStore.kt:289-290` |
| P1-3 | 投递/排队分支没有持久 claim：崩溃即双跑；队列纯内存，进程死即丢；排队也计入 `runCount`（额度空耗） | `SimpleAutoTaskRuntime.kt:138-172`、`AutoTaskDispatcher.kt:63` |
| P1-4 | 任务默认 `enabled=false`，但没有启用入口（无 UI、无 enable 工具）→ **建完永远不跑** | `AutoTaskTool.kt:112`、`AutoTaskStore.setEnabled` 零调用 |
| P1-5 | 自动 prompt 以普通 user 轮永久写进 history，污染后续对话；对这条消息「重新生成」会绕过 `maxRounds` 护栏 | `AgentAppState.kt:1445-1467` |
| P1-6 | 轮数上限按 HTTP 重试次数计数 → 网络抖动会把合法任务提前砍掉 | `AgentModelRetry.kt:27`、`AgentRuntimeRunExecutor.kt:385-405` |
| P1-7 | 执行期 `todo_write` 清空清单会重置审批基线：用旧正文重新审批、批准后任意加步骤不再触发复审 | `AgentPlanTool.kt:183-195`、`:287-288` |
| P1-8 | 进度条来自历史回执，取消计划后不会消失（库里清了，UI 仍显示 3/5） | `AgentChatBody.kt:1296-1308` vs 取消路径只改库 |
| P1-9 | EXECUTING 无出口兜底：模型忘记更新清单就永久停在执行态，每轮白注入正文+清单 | 收尾只靠 `todo_write` 触发 `shouldFinish`（`AgentLocalTools.kt:574-581`） |
| P1-10 | 搜索在主线程做 242KB 全文 split，且命中多时绕过「只渲染核心区」优化 | `AgentMemoryScreen.kt:511-513`、`375-388` |
| P1-11 | 搜索框无法输入空格（store 层 trim 掉尾部空格） | `AgentMemoryStore.kt:114-116` |
| P1-12 | `draftBytes` 陈旧 → 超限提示/体积显示/`canSave` 全部失真，粘贴 200KB 后毫无反馈 | `AgentMemoryStore.kt:86-94` vs `AgentMemoryScreen.kt:400` |

---

## 3. P2：一致性与技术债

**测试在保护不存在的东西**（这类最危险，因为它给人虚假的安全感）：

| 被保护的契约 | 现实 |
|---|---|
| `showsToggle`（REVIEW 不显示开关） | 生产零调用，实际 UI 用 `phase != NORMAL`，行为相反 |
| `AgentPlanModeStore` suspend 版全套 | 生产只用 `*Blocking`；而生产在用的 `cancelBlocking`/`reopenPlanningBlocking` **零测试** |
| `AgentCostSummary.byModel` 汇总 | 只测数据类，SQL 从未执行，功能不可达 |
| `conversation_todos` 的 `replaceAll` 事务 | Fake 重写了默认实现，生产 SQL **在整个测试套件里从未执行** |
| `agent_tasks` 的 Entity↔域往返 | 零测试；这正是 v26/v27 两个真 bug 的现场 |
| 迁移测试的迁移列表 | **手抄**了一份，漏写 v30 迁移时测试不会红，而生产是 `fallbackToDestructiveMigration(dropAllTables=true)` → 清库 |

**其他一致性债**：
- 记忆页：两个手写分段控件（结构与 `EtaChoiceChip` 重复）、空态手写（未用 `ListEmptyState`）、大量硬编码中文（项目有 3 套 locale）
- 计划模式：`planActive` 在角色会话里恒 true（`AgentChatBody.kt:375` 算出来但 `:436` 传的是外层参数）
- 自动任务：`readOnly` 字段永远为 true 且无人能置 false（死护栏）；`AutoTaskDispatcher.clearAll()` 零调用；`describeRun/explainIdle/overview` 是为不存在的页面写的
- 注释与实现矛盾 7 处（记忆页「32K 预算」实际 8000、「渲染量小」实际可能全文等）

---

## 4. 返工模式诊断：为什么修了这么多次

从 100 个提交看，返工集中在三处，且**每次都在修症状**：

| 模块 | 返工次数 | 真实根因 |
|---|---|---|
| 自动任务 | 20+ 次 fix | **调度权威在 App 之外**（脚本不在仓库）。修触发、修投递、修导出，都在修一条无法被点火的链路 |
| 记忆页 UI | 6 次 fix | **先做功能后想信息架构**：热力图 → 折叠 → 删热力图，三轮都在推翻上一轮的布局 |
| 计划模式 | 2 轮大改 | **入口形态没定**：斜杠命令 → 工具栏 pill → 加号菜单+chip，每次都在改入口而不是改约束 |

一条可复用的教训：**当一个功能连续两轮 fix 都只是「换个位置/换个形态」时，说明缺的是设计决策，不是实现细节。**

---

## 5. 建议的修复顺序

**第 1 批（1 天内，全是小改动、高收益）**
1. P0-1 按模型卡片接线（1 行 + 1 个测试）
2. P0-2 REVIEW 时 chip/菜单退出无效（改用 `showsToggle`，删死代码或复活它）
3. P1-11 搜索不能打空格（store 不 trim）
4. P0-3 整理前加脏检查（复用切换目标的确认逻辑）

**第 2 批（数据安全，必须先于任何发版）**
5. P0-4 三张表纳入备份 + 恢复提示
6. 迁移测试改为**复用生产迁移列表**（消灭"漏写 v30 就清库"的隐患）
7. `agent_tasks` 删会话时清理 / 任务执行前校验会话存在

**第 3 批（计划模式收口）**
8. P0-7 批准改精确匹配
9. P0-6 只读绕过（navigate scheme + androguard 参数）
10. P1-7 空清单重置审批基线

**第 4 批（自动任务，工作量最大）**
11. P0-5 加 App 内调度源
12. P1-1/P1-2 统一执行引擎（离线也跑一次 runtime run）
13. P1-3/P1-4 持久 claim + `enabled` 闭环

**第 5 批（一致性收尾）**
14. 记忆页 i18n + 复用统一组件
15. 清死代码（`showsToggle` 或 `cancelBlocking` 二选一保留、`AutoTaskTrigger`、`describeRun` 等）

---

## 6. 置信度说明

- ✅ 已复核：P0-1（byModel 不可达）、P0-2（REVIEW 退出无效）、`showsToggle` 死代码
- 其余结论来自四路并行只读审查，均有 `文件:行号` 证据，但**未逐条运行验证**；
  其中 P0-5（自动任务触发链）、P0-6（只读绕过）建议在动手前用 10 分钟复现确认。
- 本报告未修改任何代码。

---

## 7. 修复记录（2026-10-20 夜，第 1–4 批）

### ✅ 第 1 批：高收益修复

| 项 | 改动 | 测试 |
|---|---|---|
| P0-1 按模型卡片不可达 | `all()` 改为复用 `byModel()`；新增纯函数 `AgentCostSummary.fromModels` | 新增 3 例，其中「分组不为空」直接守住该回归 |
| P0-2 REVIEW 阶段退出无效 | chip 可见性改用 `showsToggle()`；加号菜单新增 `planActionable`，待批准时显示「计划待批准」并禁用 | 契约测试 +2 例（`showsToggle` 从死代码复活） |
| P1-11 搜索不能打空格 | `updateSearchQuery` 不再 `trim`，空白只在过滤时忽略 | — |
| P0-3 整理丢草稿 | 整理前加脏检查 + 确认弹窗（复用切换目标的 `MwiuxDialogActions`） | — |

**顺带修掉一个真实统计 bug**：`fromModels` 原先用 `fromRows` 会按「模型行数」算轮次，
导致总账的「请求轮次」等于模型个数。现在用 `models.sumOf { it.turns }`，测试覆盖。

### ✅ 第 2 批：数据安全（部分）

| 项 | 改动 |
|---|---|
| 迁移测试手抄列表 | 抽出 `DATABASE_MIGRATIONS`（唯一来源，生产与测试共用）；`SCHEMA_VERSION` 常量与 `@Database(version)` 同源 |
| 迁移链守门 | 新增「迁移链连续且覆盖到当前版本」用例；**已验证**：版本升 30 但不加迁移 → 测试立刻 2 红 |
| 删会话不清理任务 | `AgentTaskDao.deleteByConversation` + `AgentConversationStore` 接入 |

**未完成（需单独一批）**：备份/恢复纳入 `conversation_todos` / `conversation_plans` / `agent_tasks`
（要动 `EtaBackupDocument`、JSON 流、schemaVersion 与导入提示，属于高风险改动，不宜与其它修复混做）。

### ✅ 第 3 批：计划模式安全收口

| 项 | 改动 |
|---|---|
| P0-7 批准/取消误判 | 前缀匹配 → **整句精确匹配**（「执行前先确认备份」不再被当成批准） |
| P0-6a navigate 协议 | 只允许 http/https（拒绝 file/content/javascript/data） |
| P0-6b androguard 参数 | 字符过滤 → **token 白名单**（`-o /path` 这类写文件选项整段丢弃） |

### ✅ 第 4 批：死代码清理

- 删除 `AutoTaskTrigger` 整类（从未注册 manifest、零调用；顺手把仍被实现的 `AutoTaskRuntime` 接口迁到 `SimpleAutoTaskRuntime`）
- 删除 `AutoTaskDispatcher.clearAll()`、`AutoTaskStore.markRan()`、`AgentMemoryAction.Refresh` 及其 `when` 分支
- 删除计划 pill 遗留的 `PlanChipHeight`/`PlanChipIconSize` 与 3 个无用 import
- `showsToggle()` 从死代码复活为生产调用

**保留但已标注**：`describeRun` / `explainIdle`（有测试、逻辑完整，等任务列表页接入；暂不删以免重写）。

---

## 8. 第 5 批：备份纳入三张表（v4 schema）

### 改动

| 层 | 改动 |
|---|---|
| 备份格式 | `schemaVersion` 3 → 4；新增三段 `conversationTodos` / `conversationPlans` / `agentTasks` |
| 文档类型 | `EtaBackupDocument` 补三个 List 字段（与 conversations/messages 同款：让序列化器认识这些 key，读时由流式层拆成独立文件） |
| DAO | `ConversationTodoDao.listAll()` / `ConversationPlanDao.listAll()` |
| 导出 | 三个 `beginArray` + 逐条 `record` |
| 恢复 | 仅当 `schemaVersion >= SCHEMA_VERSION_PLAN_ASSETS(4)` 才清空并恢复三表；旧备份**保留设备现有数据** |
| 实体 | 三个实体加 `@Serializable` |

### 为什么这么改

旧行为是「恢复时无条件 `deleteAllConversationTodos/Plans`」，注释的理由是"避免旧计划挂到被重映射的会话上"。
但代价是：**导入任何备份都会清空本机所有清单与计划**，而备份里根本没有这些数据 —— 缺数据不等于要删数据。
现在改成按版本门控：v4+ 才认为是"备份包含该资产"，才做清空重建；v3 及更早一律保留本机。

### 测试

新增 `exportAndImportRestoresPlanAssetsAndAutoTasks`：建会话 + 写清单/计划/任务 → 导出 → 清空设备 → 导入 →
断言清单 subject/status、计划 markdown 与 approvedVersion、任务标题与 `enabled` 全部恢复。

> 同文件另有 2 个既有失败（`exportAndImportRestoresProvidersConversationsAndMemory`、
> `memoryWriteFailureRollsBackDatabaseAndEarlierMemoryFiles`），断言点是「导出后立刻读
> `AgentMemoryRepository.snapshot()`」，属于 Windows 本地记忆文件的平台差异（基线 47 个之一），与本次改动无关。

### 顺带修复：记忆单例的测试顺序依赖（比基线还少 2 个失败）

改动过程中全量出现 3 个新的 `AgentMemoryGetPagingTest` 失败（单跑必过）。根因不是备份改动，而是：

- `AgentMemoryRepository.init()` 用 `if (!::store.isInitialized)` —— **只在首次绑定**；
- Robolectric 每个测试类新建一个 Application（`filesDir` 不同），单例仍指向第一个类的目录；
- 于是后续测试类"写入成功但读出来是空的"，只在跑全量时复现。

**修复**：加 `rebindForTests(context)`（`@VisibleForTesting`），在 `AgentMemoryGetPagingTest`、
`EtaBackupRepositoryTest` 的 `@Before` 里重绑当前 Application。

结果：全量从 50 → **45** 个失败，比改动前的基线 47 还少 2 个
（`EtaBackupRepositoryTest` 的那 2 个"记忆读空"失败，本来就是这个根因）。
