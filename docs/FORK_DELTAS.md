# Fork 改动面清单（对上游 c8e05d6）

> 这份文档是「以上游为基线、逐块恢复 fork 能力」的施工图与验收基准。
> 每次从上游同步后，按本文档逐条核对，防止能力静默丢失。

## 基线

| 项 | 值 |
| --- | --- |
| 上游基线 | `c8e05d6`（2026-10-21，feat(voice): 关闭语音识别与文字唤醒过渡） |
| 分叉点 | `4d760b8`（上游 v3.3.0，2026-10-07） |
| 上游领先 | 21 个提交（含 v3.3.1 与 10-20、10-21 的修复） |
| 基线可编译性 | 已验证：`:app:compileDebugKotlin` BUILD SUCCESSFUL |

## 改动面总量

- fork 独有 Kotlin 文件：**56 个**（上游完全没有）
- 共享文件中被 fork 改过：**128 个**（相对分叉点）
- 合计需要恢复/重接线：**184 个文件**

## 分块清单（按恢复顺序）

### ① 计划模式（只读阶段 / 审批 / 退出）

- 独有 13 个：`agent/plan/*`（阶段机、审批、计划上下文投影、退出处理）
- 相关独有：`agent/model/AgentPlanToolCatalog.kt`、`AgentPlanContextProjection.kt`
- 共享改动：`ui/app/AgentAppState.kt`、`ui/components/AgentChatBody.kt`、
  `ui/components/AgentChatInputBar.kt`、`ui/model/AgentChatUiState.kt`、`agent/tool/AgentLocalTools.kt`
- 口径约定：**「模型能看到的」==「运行时允许的」**（目录级隐藏写工具）
- 验收：`AgentToolCatalogPlanPhaseTest`（5 例）+ 真机跑「提交计划 → 审批 → 执行 → 进度条」

### ② 自动任务

- 独有 14 个：`agent/autotask/*`（Task/Scheduler/Dispatcher/Notifier/History/Export）
- 共享改动：入口接线（设置页、工具 `create_auto_task`）
- 验收：`AutoTask*Test` 四个测试类

### ③ 工具目录与能力页

- 独有：`AgentAndroguardToolCatalog.kt`、`AgentToolCallValidator.kt`（上游在「简化工具执行」里删了，
  我们的 `TerminalToolContractTest` 仍依赖）
- 共享改动：`agent/model/*ToolCatalog.kt`（8 个文件）、`ui/components/AgentToolActivity.kt`、
  能力页 `AgentToolsCatalogUi`
- 验收：`AgentToolsCatalogCoverageTest`、`ToolIconsCoverageTest`、`ToolBadgeContractTest`

### ④ 记忆增强

- 独有 4 个：`agent/memory/*`；共享改动：`ui/screens/memory/AgentMemoryScreen.kt`（含三语）、
  `data/repository/AgentMemoryRepository.kt`
- 验收：记忆页搜索/归档/整理流程 + `memory_*` 三语 key 齐全

### ⑤ 用量与成本

- 独有：`agent/cost/AgentCostRepository.kt`、`AgentCostSummary.kt`、`ui/screens/usage/AgentUsageScreen.kt`
- 共享改动：`ui/app/AgentAppRoot.kt`（入口）
- 验收：用量页有数据、成本面板聚合正确

### ⑥ 终端与后台命令

- 独有：`ui/screens/terminal/TerminalPlaceholder.kt`；共享改动 16 个（`RootShellTerminalController`、
  `ShellProcessSupervisor`、`ConsoleStore` 等）
- 注意：上游 v3.3.1 重构了 PTY 与工具执行，我们是**选择性子集**（保留 fork 自研），恢复时要逐处确认

### ⑦ OCR / 媒体

- 独有：`agent/media/ScreenTextRecognizer.kt`
- 构建依赖：**ML Kit 文字识别**（上游 `build.gradle.kts` 没有，必须补回依赖，否则整片报红）
- 验收：编译 + 真机 OCR 一次

### ⑧ 其余零散

- 品牌 logo 映射、语音微调、UI 细节（`ui/voice`、`ui/pages`、`ui/markdown` 等 30 处共享改动）

## 恢复原则

1. **每块一个提交**，编译 + 对应测试通过才进下一块；失败单独回退，不牵连其他块。
2. 共享文件一律**以上游为实现底座**，只把 fork 语义加回去；不允许整份回滚到旧实现。
3. 每块完成即在本文件打勾，并记录「与旧版的差异」，方便日后同步。
4. 全部完成后，跑一次全量单测，用例数应回到 **1974 例（0 失败 / 1 跳过）** 并保持。

## 验收基线（当前 main 的记录）

- 单测：1974 例 / 1973 通过 / 0 失败 / 1 跳过（CI run #234）
- CI：`Lint 与 Test` + `Debug/Release APK` 双 job 全绿
