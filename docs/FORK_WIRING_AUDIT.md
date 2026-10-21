# Fork 内容接线审查报告（4.0 重建回归专项）

> 2026-10-22 · 分支 `rebased-on-upstream` · 方法：以旧 fork（`origin/main` 3.14.1）为行为参照，
> 对「实现 → 注册 → 入口 → 派发」全链做接线核对，专抓「文件在、接线断」型回归。
> 起因：记忆系统改造中挖出 `ShortMemoryRepository.init()` 丢失（短期记忆整条线静默不可用），
> 同型问题很可能不止一处——本报告是那次专项审查的完整结论。

---

## 一、审查覆盖

| 维度 | 范围 | 结论 |
|---|---|---|
| 骨架接线 | EtaApp init 链 / AndroidManifest / 工具名集合 / AppRoute | ✅ 全对齐（差异均为弃用项） |
| runtime + context | 上下文四件套、中断恢复、修订回滚、nudge/402、归档/用量链 | 🔴×3 🟡×4 |
| 工具 + 提示词 | androguard 全链、参数校验、memory 派发、提示词补丁、声明条件 | 🔴×1 🟡×1 |
| UI 功能入口 | 用量页、拍照/附件、视觉开关、记忆页、androguard 入口、语言切换、无障碍标签 | ✅ 全通（🟡×1） |

## 二、发现与修复（🔴 全部已修）

### 🔴 1. 上下文校准馈线断（与 ShortMemory 同型）
`AgentLoop` 每轮的 `context.observeBudget(...)` 调用在重建时随上游覆盖丢失，
`AgentContextBudget` 的校准机制（真机实测 4.82 倍偏差修正）整条失效——
`calibration` 恒为 1.0，硬裁剪/压缩判据全部退化为未校准估算。
单测全绿测不出（测试直接喂 budget，绕过 AgentLoop）。
**修复**：AgentLoop.kt 恢复两连调（observeInputTokens + observeBudget）。

### 🔴 2. 硬裁剪丢失历史的会话内通知断
`AgentEvent.HistoryTrimmed` 事件照发，但 `AgentAppState` 的处理分支丢失——
用户只在浮层看到，会话记录里毫无痕（违反 M1.2「真的丢了历史必须可观测」）。
**修复**：AgentAppState 事件分支恢复 SystemNotice 注入。

### 🔴 3. 容量守卫 guardHistoryPrefix 丢 3 个调用点
C5 守卫本体完好，但「重新生成」（角色/普通两分支）与「续跑」的调用点全丢——
超窗重发/续跑会把必败请求发给 Runtime（重演运行失败 + 重试退避空烧）。
**修复**：三处调用点回补；续跑拦截时保留待续跑标记供压缩后重试。

### 🔴 4. buildMemorySystemMessage 的 fork 提示词增强整段丢失
退回上游简版，丢 5 处：写入模式细化+报错自救引导、短期/长期两层说明（短期记忆
「真机上永远是空的」的病根）、core_sections 字段、截断时的精确续读坐标（coreNextLine）、
headingWarning 结构提醒块——且后两者成了无人消费的死数据。
**修复**：5 处增强回搬（保留 pin 快照说明行）。

### 🟡 修复的次要项
- **续跑补载+防重入**：会话被卸载后点续跑原先静默无反应；改为 `ensureConversationLoaded`
  补载后续跑，会话已删则清标记。
- **orphanRewrites C8 竞态守卫**：`runId == currentRunId` 跳过——防误杀窗口内新发起的
  改写（改写内容丢失 + 假「已中断」提示）。
- **nudge 注入语义**：恢复每轮至多一条（重复调用优先于空回合），不再双提醒同注。
- **相册多选上限**：`maxMediaItems = 9` 回补。
- **工具参数 schema 校验接线**：minLength/互斥等目录约束从「只声明+测试锁」恢复为
  执行器内运行时执行（`validateDeclared`，只校验已声明工具，legacy 名不受影响）。
  顺序上权限门禁先于 schema 校验（「未启用」优先于「参数不合法」，与既有测试契约一致）。

### ✅ 确认无损的差异（弃用/死代码/上游覆盖）
- 计划模式（AgentPlanToolCatalog、planOverview 注入、审批条）——钦定弃用；
  planOverview 死参数仍在 Compactor 签名里，标注为待清理。
- 自动任务 AutoTaskService、GUI/OCR（locate_on_screen）——钦定弃用。
- `AppRoute.Chat` 死路由——旧 fork 自身无任何导航可达，Home 已覆盖聊天。
- `tokenUsageAll` 无调用方——v3.13.9 有意重构（all() 改走 byModel 统一口径）。
- androguard 图标 GppGood（旧 Security 在当前图标库不存在）——映射链完整。

## 三、方法论教训（下次重建/大合并要做的）

1. **「文件存在」不是「功能存在」**——重建比对以「行为链」为单位验收，
   每条功能走通 实现→注册→入口→派发 四环，而不是 diff 文件清单。
2. **init/调用点是高危面**：本轮 4 个 🔴 里 2 个（observeBudget、ShortMemory.init）
   都是「实现完整、唯一调用行丢失」。凡 fork 在上游同名文件里加过的调用行，
   合并后必须逐行点名核对。
3. **测试盲区要心里有数**：UI 层零测试、单例 init 无测试、测试直喂依赖绕过装配层——
   这三类盲区正好覆盖了本轮全部 🔴。给装配层加最小冒烟（本仓库已有
   MemoryRepositoriesInitTest 先例）比加功能测试更防这类回归。
4. **提示词是代码**：prompt 文案补丁与代码补丁同等对待，合并时同样要判定表核对。

## 四、验收

- 修复提交随本报告同批推送；CI 双 job 全绿为准入门槛。
- 靶向测试（PromptBuilder/ToolCallValidator/ToolCatalog/LocalToolsPermission/
  ModelClientLoop/ContextBudget/ContextSession）本地全绿。
