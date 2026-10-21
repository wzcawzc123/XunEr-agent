# Fork 改动面清单（对上游 c8e05d6）

> 由 git 历史自动生成（分叉点 `4d760b8` → `main`），非人工归类。
> 本文件是「以上游为基线、逐块恢复 fork 能力」的施工图与验收基准。

## 一、基线

| 项 | 值 |
| --- | --- |
| 上游基线 | `c8e05d6`（2026-10-21，支持关闭语音识别并优化文字唤醒过渡） |
| 分叉点 | `4d760b8`（上游 v3.3.0，2026-10-07） |
| 上游领先 | 21 个提交 |
| 基线可编译 | 已验证，`:app:compileDebugKotlin` BUILD SUCCESSFUL |
| 工作分支 | `rebased-on-upstream` |

## 二、总量

| 口径 | 数量 |
| --- | ---: |
| fork 行为提交（feat/fix/refactor/perf/test） | 210 |
| 其中已弃用 | **52** |
| **实际需要恢复** | **158** |
| 新增文件（全类型） | 221 |
| 改动文件 | 152 |
| 功能域 | 50 |

## 三、已确认弃用（2026-10-21）

| 功能 | 提交 | 连带去掉了什么 |
| --- | ---: | --- |
| 计划模式 | 17 | todo 清单与执行进度条；`enter_plan_mode` / `exit_plan_mode` / `todo_write` 三个工具；只读阶段的目录级隐藏机制 |
| 自动任务 | 27 | `create_auto_task` 工具；前台服务 / 通知渠道 / 开机唤醒权限 |
| GUI / OCR | 8 | 屏幕定位与 OCR 通道整块改用上游；`locate_on_screen` 工具不再存在；ML Kit 依赖不再需要 |

> 收益：恢复量从 210 降到 158 条；工具少 4 个，提示词更短，当初为治「小模型误选写工具」而做的目录级隐藏也不再需要。

**两项已定（2026-10-21 18:16）**

1. **todo 清单 / 进度条不保留**，不设极简版。上游工具集里没有 `todo_write`，重建后该能力直接不存在。
2. **`locate_on_screen` 不保留**，GUI 能力完全用上游的：`observe_screen` / `tap` / `tap_element` /
   `swipe` / `scroll`（均为无障碍树驱动）。

> 已知的能力代价（明确接受）：上游没有 OCR 通道，当无障碍树稀疏（Flutter、游戏、自绘控件）时，
> 模型只能靠截图目测坐标——这正是 fork 当初加 OCR 要解决的问题。重建后该兜底不存在。

## 四、恢复顺序（12 块）

| # | 功能块 | 提交 | 主要涉及目录 |
| ---: | --- | ---: | --- |
## 四之四、第 2 块进展与取舍

| 项 | 结论 |
| --- | --- |
| 参数体检（v3.8.1–v3.8.4 四条） | ✅ 已落地：`validateArguments()` 挂在 `AgentLocalTools.execute()` 入口（executor 层）。CI #239：**1707 例 / 1706 通过 / 0 失败 / 1 跳过** |
| `AgentFileToolCatalog` | ✅ 取 fork 版：`path` 补 `minLength=1`、`read_file` 补 offset_bytes/start_line 互斥 |
| 终端 `runCommand`/`terminal` | ⏭ 不搬：上游有 `terminal` / `terminal_job` / `terminal_daemon` 新设计 |
| 坐标新鲜度 | ⏭ 跳过：属 GUI 行为（已定 GUI 不动） |
| `androguard_analyze` | ⏸ **推迟到第 6 块**：工具本体可搬，但安装入口在 `LinuxEnvironmentScreen`（与上游差 1376 行），没有安装 UI 的工具等于不可用，不做半成品 |
| 能力页 `AgentToolsCatalogUi` | ⏭ 不搬：上游已有同名页面（153 行），fork 版是 188 行的增强，差异 341 行，先用上游 |

| ✅ 1 | 上下文预算 / 裁剪 / 压缩（20 条，跳过 1 条上游已覆盖） | 22 | `agent/model`、`docs/releases`、`test/agent` |
| 2 | 工具执行链路（含 工具目录、工具参数校验） | 9 | `agent/model`、`test/agent`、`agent/tool` |
| 3 | Agent 运行时（含 Agent 循环、运行时执行） | 12 | `agent/model`、`test/agent`、`agent/runtime` |
| 4 | 记忆机制（含 记忆页） | 19 | `test/agent`、`agent/memory`、`agent/model` |
| 5 | 提示词 | 5 | `test/agent`、`agent/model` |
| 6 | 稳定性（含 守卫、契约） | 7 | `agent/model`、`test/agent`、`ui/app` |
| 7 | 界面细节（含 聊天） | 23 | `agent/model`、`ui/components`、`test/ui` |
| 8 | 角色扮演 | 5 | `agent/model`、`agent/roleplay`、`ui/screens` |
| 9 | 用量统计（含 成本面板） | 6 | `agent/cost`、`ui/screens`、`test/agent` |
| 10 | 附件与相机（含 图片 I/O、图片输入开关） | 5 | `agent/model`、`app/src`、`test/agent` |
| 11 | 多语言（含 无障碍） | 5 | `app/src`、`ui/components`、`ui/screens` |
| 12 | 其余零散（22 个域：合并上游适配、测试、悬浮层、数据库、Markdown、语音、终端、Lint 等） | 40 | — |

## 四之二、进度

| 块 | 状态 | 验收 |
| --- | --- | --- |
| ① 上下文 | ✅ 完成（`e1e0b95` + `54576a0`） | 分支 CI #237 双 job 绿：**1690 例 / 1689 通过 / 0 失败 / 1 跳过**；判掉 1 条上游已覆盖（cache_control）|

## 四之三、第 2 块（工具执行）判定与拆分

### 判定表（7 条可搬 + 1 条跳过 + 1 条部分）

| 提交 | 上游命中 | 判定 |
| --- | ---: | --- |
| `fix(tool): fileTool 分发补回 terminalTool 权限门` | 100% | 跳过（上游已覆盖） |
| `feat(tools): androguard_analyze` | 25.7% | 部分：只搬工具与安装器，不搬环境适配 |
| `fix(tool)` v3.8.1–v3.8.4 四条 | 0–5.8% | 搬 |
| `fix(tools): 能力页补全 26 个工具` | 3.6% | 搬 |
| `fix(tool-validation): 全角 XML 粘包引导` | 2.3% | 搬 |

### 关键发现：4 条提交都围绕 `AgentToolCallValidator`

v3.8.1–v3.8.4「报错诊断」系列（全角等号粘包、分支标签、合法取值、截断提示）
全部改的是这个校验器。它不是死代码，是 4 个真机问题的修复。

**放置决策**：上游契约禁止 Loop 级 schema 预检（`toolArgumentsReachExecutorWithoutSchemaPreflight`），
但诊断能力不能丢 → 校验搬到 **executor 层**（`AgentLocalTools` 的真实分发处）。
测试里的 lambda executor 自然绕过，生产路径仍然生效。

### `AgentLocalTools` 函数级拆分（我们 2237 行 / 上游 1481 行）

多出 19 个函数，其中 **10 个属于已弃用功能，不搬**：

- 自动任务 4：`createAutoTask` / `deleteAutoTask` / `listAutoTasks` / `exportAutoTasks`
- 计划模式 3：`enterPlanMode` / `exitPlanMode` / `todoWrite`
- GUI/OCR 3：`locate` / `locateViaOcr` / `visibleTextHint`

需要搬的 9 个：

| 函数 | 归属 |
| --- | --- |
| `androguardAnalyze` | 本块 |
| `runCommand` / `terminal` | 本块（终端工具） |
| `annotateDefaultedEnvironment` / `isValidTerminalEnvironmentArgument` | 本块 |
| `ensureCoordinateFreshness` | 本块（坐标新鲜度） |
| `decodeImageBitmap` | 第 10 块（图片） |
| `isArchivedRead` / `memoryRepositoryFor` | 第 4 块（记忆） |

上游独有 `checkRoot` 需保留，不做整份替换。

## 四之五、第 3 块（Agent 运行时）进展

判定表（12 条）：跳过 1（runtime 压缩 60.4% 上游已覆盖）、已在第 1 块落地 3 条
（HistoryTrimmer 接线 ×2、脱敏回放）、本块已搬 2 条、待搬 5 条。

| 提交 | 状态 |
| --- | --- |
| `feat(loop): 检测连续重复的工具调用并提醒模型` | ✅ 已补回（换 Loop 时丢的）：连续 3 次同工具同参数 → 下一轮请求附系统提醒，只进出站副本；只提醒不拦截 |
| `fix(agent): HTTP 402 明确提示余额或额度不足` | ✅ 已搬：402 归入不可重试，提示改为「请充值或更换 API Key」 |
| `fix(runtime): 系统回收中断不再误判失败` | ⏳ 待搬（中断语义 + 续跑入口） |
| `fix(agent): 检查点兜底 history 有界` | ✅ 已搬：迁移兜底从「全部正文」改为 40 条 / 32k 字符双上限（长会话 800 万字符是「频繁压缩+运行失败」的根因），取入 LegacyHistoryBoundsTest |
| `fix(agent): 参数可自查 + 下载目录 + 备份流式写盘` | ⏳ 待搬（三件混合，需拆） |
| `fix(agent): environment 判定大小写不敏感 + 备份残留清理` | ✅ 已搬：`when(lowercase())` 修标签；导出失败删除残留文件 |
| `fix(runtime): 实现上下文压缩并保留脱敏历史` | ⏭ 上游已覆盖 60.4% |

验收：CI #241 双 job 绿，**1713 例 / 1712 通过 / 0 失败 / 1 跳过**

## 四之六、第 4 块（记忆）进展

判定表（19 条）：跳过 1（记忆上下文按会话固定，95.5% 上游已有）、部分 3、搬 15。
本轮先落后端（UI 与三语下一轮）：

| 项 | 说明 |
| --- | --- |
| 新增 | `ShortMemoryRepository`（短期层）、`MemoryRollup`（整理）、`AgentMemoryFreshness`（时效）、`AgentMemoryHeadingCheck`（同名检测）、`AgentMemoryPlacement`（位置建议）、`AgentMemoryPreview`（预览核心区） |
| 替换 | `AgentMemoryRepository` 606 vs 350（多 10 个函数，上游无独有）、`AgentMemoryContext` 217 vs 86（Builder + 短期分层）、`AgentMemoryToolCatalog` 144 vs 103（section/水位/归档/分页） |
| 工具实现 | `memoryGet` / `memoryWrite` 换成 fork 版（94/78 行），补 `MemoryPageKey`（拦重复读同一页）、`memoryRepositoryFor`、`isArchivedRead` |
| 测试 | 取入 10 个记忆测试；`AgentToolCatalogTest` 断言对齐 fork 契约（mode 含 `replace_section`、`section` 字段、必填仍只有 mode+revision） |

验收：CI #243 双 job 绿，**1771 例 / 1770 通过 / 0 失败 / 1 跳过**
（记忆相关 100 例含：ContextBuilder / Freshness / HeadingCheck / Placement / Preview / Rollup / GetPaging / ArchiveBoundary / Store）

**未做**：记忆页 UI（`AgentMemoryScreen` 727 行 vs 上游 467）与 `memory_*` 三语、热力图已弃用。

## 四之七、第 5 块（提示词）完成

判定 5 条全部 0% 命中，但只有 2 条适用：

| 提交 | 状态 |
| --- | --- |
| `feat(prompt): 禁止断言上下文占用百分比` | ✅ 搬：真机实例「界面 36.9%、模型称 99%」，明确「没有该数据来源」并给出正确回应方式 |
| `fix(prompt): 先确认 environment 再选路径` | ✅ 搬：真机 417 次 terminal 失败（占 61%）源于在 Android 环境试 Linux 路径；明确 Android 无 `/workspace`，需要 Linux 而没装时停止试探 |
| `fix(prompt): 补字符串连接符` | — 上一条的修复，随实现一起落地 |
| `refactor(prompt): 删 6%/PIL SOP` + `fix(prompt): 视觉管线缩放` | ⏭ 跳过：都是 OCR/目测坐标时代的提示词，随 GUI/OCR 弃用 |

验收：CI #244 双 job 绿，**1771 例 / 1770 通过 / 0 失败 / 1 跳过**

## 四之八、第 6 块（稳定性）评估结论：并入第 7 块

判定 7 条后逐条核对「这些修复在当前分支落地多少」：

| 提交 | 当前落地 | 缺口在哪 |
| --- | ---: | --- |
| `fix(stability): 批次A 高危五项` | 32.7% | `AgentAppState` / `AgentChatBody`（脏态守卫、懒加载空态） |
| `fix(stability): 批次B 中危八项` | 24.5% | 同上 + `AgentAppState`（压缩风暴残留） |
| `fix(stability): C3a 校准 0.4` | 36.2% | 校准本身已随第 1 块 Budget 完整落地（0.4 初值、实测覆盖），缺口是 AppState 侧遥测 |
| `feat(guard): 参数卫生（XML 粘包）` | 53.7% | 已由第 2 块 `validateArguments()` 完整覆盖 |
| `fix(stability): H3 污染态判据` | 0% | 整块是 fork 的懒加载守卫（`messagesLoaded` / `emptyPollution`），上游没有这个机制 |
| `fix(stability): L14 熔断测试` | — | 测试对象是 `locate_on_screen`，随 GUI 弃用 |

**结论**：本块没有可独立交付的增量——凡是能单独搬的（校准、参数卫生）都已在前面块落地；
剩下的全部依赖 `AgentAppState` / `AgentChatBody` 的 fork 版本（懒加载、脏态守卫），
而那正是第 7 块（界面细节，23 条）要处理的同一批文件。**合并进第 7 块，避免同一批文件改两遍。**

## 五、恢复原则

1. 每块一个提交，编译 + 对应测试通过才进下一块；失败单独回退。
2. 共享文件以上游为实现底座，只把 fork 语义加回去；禁止整份回滚旧实现。
3. 每块完成在本文件打勾，并记录「与旧版的差异」。
4. 全部完成后全量单测应回到 1974 例（0 失败 / 1 跳过）。

## 附录 A：待恢复提交（158 条，按功能域）

### 上下文预算 / 裁剪 / 压缩（`context`）· 22 条

- feat(context): 压缩完成时把实测占用写入摘要（缓存友好的一次性注入）
- fix(context): 压后超线判定改用实测 input tokens 作基准（P1-2 口径统一）
- fix(context): 区分「降幅不足未生效」与「压缩失败」的提示文案
- fix(context): 校准初值改为中性 1.0，消除方向性偏差
- fix(context): compactEnd 保留成本不再乘 calibration，修复保留量超预算
- fix(context): 压缩降幅判定改用字符数，修复摘要被误拒
- feat(context): 合并上游 Anthropic cache_control 断点（前缀缓存命中增强）
- test(context): 修复懒加载单测 previews 断言 —— 值为单条消息而非列表
- test(context): 修复懒加载单测断言类型 —— history 应为模型消息而非 UI 消息
- fix(context): 内存基线治根 —— 启动只载选中会话，其余懒加载
- fix(context): 容量守卫固定开销动态化 —— 实测 input_tokens 代替常量，根治漏拦盲区
- fix(context): 上下文板块完善 —— 容量守卫补 system/schema 预留 + 续跑知情缓冲
- feat(context): 四项收口（校准跨 run 保守初值 / 摘要小节校验+重试 / XML 引导示例）
- fix(context): 修复本地估算高估 4.82 倍导致的误裁剪（激活从未接上的校准）
- feat(context): 工具结果修剪器（照 deepseek-harness 设计 + 本机实测参数适配）
- fix(context): 摘要长度纪律 + 多代检查点，修 _eta_context_summary 键名 (v3.9.1)
- feat(context): 压缩策略对齐 DeepSeek Harness——80% 触发 + 逐字保留 + 需求锚点 (v3.9.0)
- fix(context): R6 熔断期裁剪兜底——压缩失败后裁剪线降到触发线
- fix(context): 上下文成本护栏——触发线上限、压缩失败熔断、降幅门槛
- fix(context): 审查C1 — 硬裁门去掉 usageObserved 前置，新鲜run round1超窗即裁 (v3.7.1)
- feat(context): M1.2 上下文失败可恢复 — compact 降级硬裁 + CONTEXT_EXHAUSTED + HistoryTrimmed 事件
- feat(context): 压缩触发线保持 fork 策略 0.75，测试边界跟随阈值

### 工具执行链路（`tool`）· 6 条

- fix(tool): 真机全量实测驱动的两处报错修复 (v3.8.4)
- fix(tool): 与请求 action 同名的分支原因优先展示 (v3.8.3)
- fix(tool): 报错诊断再升级——分支标签/违规字段/合法取值/截断提示 (v3.8.2)
- fix(tool): 终端报错可诊断化 + 旧工具名迁移引导 (v3.8.1)
- fix(tool): fileTool 分发补回 terminalTool 权限门（merge 缝合遗漏，CI 定罪）
- fix(tool): 坐标新鲜度增加分辨率切换判据 + read_image 返回图片尺寸

### 工具目录（`tools`）· 2 条

- fix(tools): 能力页补全 26 个工具，加图标覆盖与徽标契约测试
- feat(tools): 新增 androguard_analyze APK 静态分析工具（Linux 工具环境）

### 工具参数校验（`tool-validation`）· 1 条

- fix(tool-validation): 工具调用格式错乱引导加固 —— 全角 XML 粘包漏检 + 解析失败诊断 + Anthropic input 保真

### Agent 运行时（`agent`）· 8 条

- fix(agent): 坐标新鲜度防护与脱敏回放识别
- fix(agent): 上下文压缩触发线从 85% 降至 75%，留出安全余量
- fix(agent): AgentHistoryTrimmer 改为在 AgentLoop 请求前兜底，续跑路径同步加固
- fix(agent): 在 AgentPromptBuilder 前接入 AgentHistoryTrimmer，防止超窗请求导致 Runtime 失败
- fix(agent): HTTP 402 明确提示「余额或额度不足」，不再只报状态码
- fix(agent): 检查点兜底的 history 改为有界（防御性加固）
- fix(agent): environment 判定恢复大小写不敏感，备份失败清理残留文件
- fix(agent): 让参数问题可自查 + 修正下载目录数据源 + 备份流式写盘

### Agent 循环（`loop`）· 2 条

- feat(loop): 检测连续重复的工具调用并提醒模型
- test(loop): 覆盖 run_command 复读路径——拦在能力目录层且不触达执行器

### 运行时执行（`runtime`）· 2 条

- fix(runtime): 系统回收中断不再误判失败 —— 中断语义区分 + 续跑入口
- feat(runtime): 实现上下文压缩并完整保留脱敏历史

### 记忆机制（`memory`）· 14 条

- perf(memory): 预览只渲染核心区 + 短期记忆补上提示词引导
- feat(memory): 内容时效标记（记忆 P1-3 · 过期）
- feat(memory): 同名标题检测（记忆 P1-2 · 去重）
- feat(memory): 写入时给出位置建议（记忆 P1-1 · 写入分层）
- feat(memory): memory_get 标识归档区读取（记忆改造 · 第三层·检索质量）
- feat(memory): memory_get 回报核心区水位（记忆改造 · 第一层：可观测）
- fix(memory): 记忆升级终审 P1+P2 — 文档标题区分长短/切换目标防丢草稿
- feat(memory): 记忆升级四机制落地 — 短期分层/rollup 整理/记忆中心 UI 增强/会话热力图（spec memory-upgrade）
- feat(memory): 合并上游记忆上下文按会话固定（保请求前缀缓存）
- feat(memory): 记忆写入失败自救引导 —— 增强 MEMORY_RANGE_INVALID 提示 + 系统提示补自救说明
- feat(memory): M1.3 重复页程序化拦截 — DUPLICATE_PAGE 强制续读
- fix(memory): memory_get 回传分页续读指引，并撤回「记忆结果可见」豁免
- fix(memory): 修正 replace_section 误拦"空 content 删除整节"，并补齐 3 条未覆盖的失败路径
- feat(memory): 完善记忆系统 —— 按章节读写、核心段合并注入、参数问题可自查

### 记忆页（`memory-ui`）· 5 条

- fix(memory-ui): 记忆搜索没反应 — 预览搜全文（含归档区）+ 编辑输入自动切预览 + 空命中提示 + 删死代码
- fix(memory-ui): 记忆编辑「放弃」后保存栏残留 — DiscardDraft 独立清脏（不复用永远置脏的 DraftChanged）
- fix(memory-ui): 记忆页编辑卡顿治本 + UIUX 精简（删热力图/去折叠/色点）
- refactor(memory-ui): 概览区可折叠，正文区不再被挤成一条
- fix(memory-ui): 切换按钮被设置卡片压住 + 热力图裁掉两行

### 提示词（`prompt`）· 5 条

- fix(prompt): 补上遗漏的字符串连接符（上一提交编译失败）
- fix(prompt): 明确「先确认 environment 再选路径」，收敛环境试探空转
- feat(prompt): 明确告知模型无法感知上下文占用，禁止断言用量百分比
- refactor(prompt): M1.1 文案净化 — 删除 6%/PIL 个案 SOP，OCR 落地后按方案时序解锁
- fix(prompt): 目测坐标系统性偏差的根因修复——视觉管线缩放

### 稳定性（`stability`）· 5 条

- fix(stability): L14 熔断测试先 observe 建立观察快照——tap_element 需 observation 定位节点
- fix(stability): C3a 校准初值 0.4 + C3b usage 遥测 + 低危四项（重复失败熔断/失败不递增版本/volatile/死代码）
- fix(stability): 体检批次B中危八项 — 压缩风暴残留/裁剪出图/roleplay开销/守卫缺口/journal幽灵分支/rewrite残留/恢复竞态/工具交错
- fix(stability): H3 污染态判据改查库内消息数——旧基线为空（save 每次 new persistence）时也能拦截
- fix(stability): 体检批次A高危五项 — 签名回合裁剪守卫/瞬态键保留、裁剪校准口径、懒加载空态污染、编辑重发守卫窗口、OCR node_index 错位

### 守卫（`guard`）· 1 条

- feat(guard): 参数卫生检查（XML 粘包残渣）+ OCR 定位协议修复

### 契约（`contract`）· 1 条

- test(contract): M2.3 坐标契约回归锁 — buildCoordinateContract 抽纯函数 + 3 例本地锁

### 界面细节（`ui`）· 19 条

- refactor(ui): 清理 planPreview 残留与 Memory.Refresh 分支
- fix(ui): 用量页统一骨架与按模型接线，记忆页整理防丢草稿
- fix(ui): 给 create_auto_task 配图标（ToolCatalogUiTest 要求每个工具都有专属图标）
- feat(ui): 合并上游浏览器与记忆页重做（60eac0e 打磨版）
- feat(ui): 合并上游模型选择器 sheet 双形态 + 搜索（保留 fork 视觉开关）
- fix(ui): 会话切换懒加载显示加载占位，不再闪空白聊天页
- fix(ui): locate_on_screen 补专属图标 — 通过 ToolCatalogUiTest 图标覆盖锁
- fix(ui): 修复从角色详情进入新对话时角色列表闪现
- refactor(ui): 移除聊天顶栏的角色身份区
- refactor(ui): 精简 GUI 操作浮层状态呈现与文案
- refactor(ui): 精简上下文压缩标记与用量弹层呈现
- refactor(ui): Linux 可选工具列表按钮收入行尾并补分隔线
- feat(ui): 对话搜索支持匹配完整消息内容
- feat(ui): 对话列表长按菜单新增导出为 Markdown
- refactor(ui): 设置页精简文案与入口
- feat(ui): 思考卡片与运行态图标改为灯泡
- refactor(ui): 中文语境下「推理」文案统一改为「思考」
- fix(ui): 权限健康页三个条目共用图标改为按语义区分
- fix(ui): androguard_analyze 补专属图标，通过工具图标完整性校验

### 聊天（`chat`）· 4 条

- fix(chat): 移除正文流式显现速度上限
- fix(chat): 修复高速思考流显示滞后与协议收尾
- fix(chat): 复用最终 Markdown 快照避免回复结束闪动
- fix(chat): 空正文回合先纠偏重试，用尽后按无文字结果收尾

### 角色扮演（`roleplay`）· 5 条

- feat(roleplay): 聊天气泡角色署名头（CharacterReplyHeader）——收官尾项
- feat(roleplay): 首页聊天舞台提供 LocalRoleplayChat 角色身份（气泡署名头前置）
- feat(roleplay): 合并上游角色头像酒馆重构（展示层 + 收藏 + 顶栏身份）
- feat(roleplay): 以删除取代归档并新增默认角色小满
- feat(roleplay): 新增可选角色扮演功能

### 用量统计（`usage`）· 3 条

- feat(usage): 用量统计重排 — 三卡合一为「结论 + 明细」
- feat(usage): 用量统计完善 — 风格统一（EtaCard/Preference 体系）+ 按模型分组 + 下拉刷新
- fix(usage): 用量统计页永远空 + 背景透明透出设置页

### 成本面板（`cost`）· 3 条

- feat(cost): 用量统计页面接入设置（成本面板 · UI 完成）
- feat(cost): 会话/全局用量聚合查询与投影（成本面板 · 查询层）
- feat(cost): token 用量汇总模型（成本面板 · 数据层）

### 附件与相机（`media`）· 2 条

- fix(media): 超大用户附件改为按需降级，修复整张附件静默丢失
- feat(media): 相机入口与图片多选、超大图按需降级、多选按选择顺序发送（含单测）

### 图片 I/O（`image`）· 2 条

- fix(image): 纯文本模型出站统一剥离图片块（恢复被 merge 丢失的 supportsVision 门控）
- fix(image): read_image 去脱敏 + 图片归因防串图

### 图片输入开关（`vision`）· 1 条

- feat(vision): 模型图片输入开关（supportsVision）

### 多语言（`i18n`）· 4 条

- fix(i18n): 记忆页 16 条文案接入三语
- fix(i18n): 把上游已资源化、我们却硬编码的六条文案接回 strings.xml
- fix(i18n): 补 route_usage_stats 的简繁中文（Lint MissingTranslation）
- feat(i18n): 增加应用语言切换选项

### 无障碍（`a11y`）· 1 条

- fix(a11y): 找回工具卡展开箭头的无障碍标签（回归自上游合并）

### 合并上游适配（`merge`）· 9 条

- fix(merge): 移除 build job 里重复的单元测试步骤（verify job 已覆盖，并发跑 Robolectric 会 flaky）
- fix(merge): 删除 workflow 中引用未定义数组的孤儿 APK 校验（Debug APK 数量异常 0 的根因）
- fix(merge): workflow 按 Gradle 实际产物名查找 APK（Eta-v*.apk 通配符）
- fix(merge): screen 坐标分支不再因设备尺寸不可用而掩盖 UNANCHORED_COORDINATE
- fix(merge): 移除中文版重复的 linux_apk_analysis_ready 键（lintDebug 本地已 BUILD SUCCESSFUL）
- fix(merge): 补回旧工具名执行路径 + 参数校验先于新鲜度检查 + 中文版补 10 条文案
- fix(merge): 统一错误码命名 INVALID_TOOL_ARGUMENTS → INVALID_ARGUMENT（对齐上游 3.3.0 契约）
- fix(merge): 补齐角色扮演工具的目录一致性与图标映射
- fix(merge): 适配上游文案重构，恢复 MCP 折叠与 Linux 工具行

### 测试（`test`）· 5 条

- fix(test): 记忆分页测试顺序依赖 — 填充量从一页增至三页
- fix(test): 测试签名跟上 canRun/pick 的 nowMillis 参数
- fix(test): 迁移测试同步登记 MIGRATION_23_24（CI 抓到）
- fix(test): 修正 root 宿主下的目录写权限前提与 SSE 冷启动超时预算
- fix(test): LocaleResourcesTest 改用 createConfigurationContext，规避 Robolectric 下 localeConfig 干扰

### 悬浮层（`overlay`）· 3 条

- fix(overlay): 合并上游外部入口任务结果卡片回到对应会话
- feat(overlay): 合并上游 Agent 手势经过状态胶囊自动让路
- fix(overlay): 横竖屏切换时重新计算 overlay 窗口参数，修复只渲染半屏

### 数据库（`db`）· 2 条

- fix(db): 显式标注绑定参数类型，消掉 Kotlin 2.6 的 reified 交叉类型预警
- fix(db): agent_tasks 的索引声明补齐（Room schema 校验）

### Markdown（`markdown`）· 2 条

- test(markdown): 吸收上游 213e8ed 完整测试（12 用例）——修复正确性获上游全测试背书
- fix(markdown): TexMathDelimiters.normalize 围栏内空行死循环（上游 3.3.1 原版 bug）

### 语音（`voice`）· 2 条

- test(voice): SpeechPlaybackTest 显式禁用流式合成（streamSynthesize = null）——controller 默认启用流式后逐段断言失效
- feat(voice): 合并上游聊天自动朗读 —— 后台/锁屏继续播放 + 整篇流式合成

### 终端（`terminal`）· 2 条

- feat(terminal): 合并上游 PTY 交互式 TUI 重构（选择性子集，保留 fork 自研）
- fix(terminal): 修正 Linux 聊天摘要误显示为 Alpine

### Lint（`lint`）· 1 条

- fix(lint): 折叠提示改用 stringResource 取模板 + String.format 填值

### CI（`ci`）· 1 条

- fix(ci): 汇总脚本支持直接传目录

### 数据层（`data`）· 1 条

- fix(data): 备份 v4 纳入清单/计划/任务，迁移链单一来源，清理死代码

### 仓库层（`repository`）· 1 条

- test(repository): 适配上游 syncRemoteModels adoptedModelIds 新签名（ModelRepositoryTest）

### Provider（`providers`）· 1 条

- feat(providers): 合并上游模型管理页重构（多选删除/编辑弹窗简化/远端勾选导入）

### 未标（`未标`）· 1 条

- fix: 合并上游终端输入管道修复 + Provider 缓存前缀只增不减

### 会话修订（`revision`）· 1 条

- fix(revision): 编辑重发 CONTEXT_OVERFLOW 根治 —— boundary ID 定位 + 发送前容量守卫

### 压缩（`compaction`）· 1 条

- fix(compaction): 手动压缩对齐 deepseek-harness —— 无可压缩内容视为成功 + 压缩计量展示

### 诊断（`diagnostics+gui`）· 1 条

- feat(diagnostics+gui): v3.7.0 — M3.3 诊断导出 + M2.2 锚定减半版 + LOCATE_MISS 换词引导

### 隐私（`privacy`）· 1 条

- feat(privacy): M1.4 敏感策略表单一真源 — 废除 read_image 豁免

### Provider（`provider`）· 1 条

- feat(provider): 支持自定义请求头编辑与 OpenCode 稳定会话标识

### 启动页（`splash`）· 1 条

- feat(splash): 添加平滑衔接首页的启动动画

### 图标（`icons`）· 1 条

- feat(icons): 更换应用图标并适配通知与桌面主题

### MCP（`mcp`）· 1 条

- feat(mcp): MCP 工具项折叠——描述默认两行省略，点击展开/收起

### 模型层（`model`）· 1 条

- feat(model): 官方目录新增 deepseek-v4-flash-vision-exp（图片多模态、reasoning、1M 上下文）

## 附录 B：已弃用域提交（52 条，仅存档）

### ~~计划模式（`plan`）· 17 条~~

- fix(plan): 只读改目录级隐藏，修审批条滞留与进度条残留
- feat(plan): 入口改为加号菜单+输入区 chip，计划进对话流并支持精确批准
- feat(plan): 计划模式闭环 — 只读规划 → 审批 → 执行，入口改为界面按钮
- fix(plan): 进度条移入输入区，消除与输入框之间的透明缝
- feat(plan): 进度条可收起/展开，并紧贴输入框
- fix(plan): todo_write 回执被压成「完成」，进度条永不显示
- feat(plan): 进度条接入输入区（第 4 轮 · UI 完成）
- feat(plan): 清单进度条组件（已就绪，暂未接线）
- feat(plan): 清单摘要解析器（第 4 轮 · UI 数据层）
- feat(plan): 压缩时把任务清单概览写进摘要（第 3 轮 · 压缩集成）
- fix(plan): 补齐迁移测试与工具图标（CI 剩余两处失败）
- fix(plan): 补齐两处必做注册（function 外壳 + 工具能力合同）
- fix(plan): optString 的 fallback 不能传 null（Android org.json 会抛 JSONException）
- feat(plan): 挂载 todo_write 到能力目录与执行链（第 2 轮 · 注册）
- feat(plan): todo_write 工具协议与同步读写通道（第 2 轮 · 工具层）
- feat(plan): 任务清单持久化（第 1 轮 · 存储层）
- feat(plan): 任务计划的数据模型与校验（第 1 轮 · 数据层）

### ~~自动任务（`autotask`）· 27 条~~

- fix(autotask): 定时任务板块修复 — 投递路径轮数硬上限 + 离线崩溃恢复 + 并发读取入锁 + 服务不导出
- fix(autotask): 离线执行读错历史源（模型零上下文 + 缓存全废）
- feat(autotask): 投递上限兜底 + 完成后自删引导（防无限烧 token）
- fix(autotask): 删除任务时清理待投递队列（修「幽灵自动轮」）
- feat(autotask): 忙碌时排队，轮次结束自动投递（补上缺的一半）
- fix(autotask): 投递改为「会话内一轮正常对话」，对齐 dsh 的 agent.followup
- fix(autotask): 真机端到端测试暴露的 5 个缺陷
- fix(autotask): 并发锁失效 + 结果入库无上限
- fix(autotask): 执行后重导出 pending.txt（否则重复任务会被反复触发）
- fix(autotask): schedule=none 被误判为非法（最常见的用法直接失败）
- fix(autotask): 深度审查第 2 轮 —— 数据完整性 / 成本护栏 / 自愈
- test(autotask): 补 ScheduleSpec 测试，并修出第三个语义 bug
- fix(autotask): 标记清理 + 单实例锁 + Trigger 标注未启用（审查续）
- fix(autotask): 重复任务能被再次触发（审查发现的两个真 bug）
- fix(autotask): 接上 pending.txt 导出（审查发现的致命断点）
- fix(autotask): 结果写回会话（此前只写库不写会话，点通知会是空的）
- feat(autotask): 系统提示写明「何时建任务」（决定自动判断是否成立）
- feat(autotask): 补齐 list / delete 工具（对齐 dsh 四件套）
- feat(autotask): create_auto_task 工具（对话中直接建任务）
- feat(autotask): root 守护触发链（队列导出 + 唤醒服务）
- feat(autotask): 任务绑定会话，执行时在原会话追加一轮（关键修正）
- feat(autotask): 接通真实工具链与隐藏会话（自动任务第 5 期 · 闭环）
- feat(autotask): 执行实现与通知（自动任务第 4 期 · 接线）
- feat(autotask): 系统事件触发层（自动任务第 4 期 · 点火器）
- feat(autotask): 状态采集与点火决策（自动任务第 3 期 · 触发器内核）
- feat(autotask): 持久化与迁移（自动任务第 2 期 · 数据层）
- feat(autotask): 任务模型 / 触发条件 / 队列选取（自动任务第 1 期）

### ~~屏幕定位(OCR)（`locate`）· 5 条~~

- fix(locate): 取证自愈重写 rename-agnostic — v3.6.3
- fix(locate): ML Kit 注册失败进程内取证+自愈 — v3.6.2 定罪闭环
- fix(locate): OCR 失败可定罪 + 冷启动超时 5s→15s — v3.6.0 三连 null 诊断版
- fix(locate): OCR 通道屏幕尺寸可选化 — 拿不到尺寸按 1:1 降级而非放弃 OCR
- feat(locate): M2.1 OCR 通道 — ML Kit 中文离线识别，树里没有≠屏幕上没有

### ~~GUI 定位（`gui`）· 2 条~~

- fix(gui): locate_on_screen 未命中不再被当成目测许可（提示词 + MISS 载荷给出可见文本）
- feat(gui): locate_on_screen 设备侧定位工具 — M2.1 治本替换目测坐标

### ~~ML Kit OCR（`mlkit`）· 1 条~~

- fix(mlkit): registrar 无参构造保活 — OCR NPE 五轮取证的根因修复(v3.6.4)

## 附录 C：非功能提交

发版 / 文档 / CI / 构建类共 89 条（release 37、docs 17、chore 21、ci 4 等），不计入恢复量。
