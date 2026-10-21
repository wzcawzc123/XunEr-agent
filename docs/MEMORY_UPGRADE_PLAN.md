# 记忆系统升级优化方案（4.x 第一波）

> 2026-10-22 · 分支 `rebased-on-upstream` · 目标：记忆系统重新构思升级 + 记忆页 UI 小优化
> 本方案是执行依据：先修断线与数据安全，再加回退能力，最后做 UI 诚实化与导航优化。

---

## 一、现状诊断（摸底结论）

记忆系统现状：`MEMORY.md` 纯 Markdown 单文件（长期 `filesDir/memory/`，短期 `filesDir/memory-short/memory/`），
Markdown 标题即分区；`# 核心记忆` 段按预算（contextWindow/16，钳位 4K..32K）注入 prompt，其余进标题索引按需读；
工具面 `memory_get`/`memory_write`（section/query/分页/乐观锁 revision）；UI 有长短切换、搜索、整理（rollup）、
预览/编辑。整体设计是好的，但摸底发现 **1 个功能全废级回归 + 1 个 UI 逻辑 bug + 一批数据安全与诚实性问题**：

| # | 级别 | 问题 |
|---|------|------|
| A | 🔴 全废 | `ShortMemoryRepository.init()` 全仓无调用（重建时 `afbf882d` 漏掉了 `EtaApp` 里的一行）→ 短期 tab、`target="short"` 工具、整理功能**全部不可用**，测试无一例覆盖所以 CI 不报 |
| B | 🔴 bug | 「整理」确认对话框被误嵌在切换目标对话框的 `let` 块内 → 有未保存修改时点整理**静默失效**；点切换目标时**双对话框叠加** |
| C | 🟡 诚实性 | 编辑中 `draftBytes` 不更新（242KB 每键 `toByteArray` 卡顿的历史取舍）→ 超限提示有失效窗口，`canSave` 判据 stale |
| D | 🟡 数据安全 | UI 保存走无锁 `replaceAll` 整文件覆盖 → **UI 编辑期间 Agent 的 memory_write 被静默吞掉**；dirty 无条件置位（改回原样也提示未保存） |
| E | 🟡 口径 | 标题级别三套标准：解析认 1-6 级、注入索引只认 1-2 级、查重认 1-3 级 → `###` 小节能读写却永不进注入索引 |
| F | 🟡 数据安全 | 备份只含长期、不含短期；备份恢复**无条件覆盖**现有记忆 |
| G | 🟡 工程 | `safeTake` 双份、`CORE_HEADING` 三份、两个同名 `AgentMemoryStore`（数据层/UI 层）、`enabledFlow()` 死代码、工具派发路径 `runBlocking` 读 DataStore、`recordOwnWrite` 把短期写入哈希也记进长期 pin、freshness 写死 `LocalDate.now()` |
| H | 🟡 能力 | 无历史/回滚（一次误编辑/误 clear 不可恢复）；整理结果直接落盘无确认；UI 不知道核心区注入水位（真机曾用到 89% 无人知晓） |

## 二、设计原则

1. **数据安全优先**：写入必须可并发（乐观锁）、可回退（历史快照）、备份要完整（长短都备）。
2. **UI 状态不许说谎**：字节数、脏标记、忙碌指示与真实状态一致；失效窗口不接受。
3. **单一真源**：标题口径、公共常量、初始化入口各只有一处。
4. **补丁要薄**：不动注入预算算法、不动工具 schema（保住上游合并健康度）、不做大重命名。
5. **每个修复配测试锚点**：这次的 A/B 都是「无测试覆盖」漏掉的，教训要落到测试上。

## 三、记忆系统升级（数据/逻辑层）

### S1 修复短期记忆断线（P0）
- 新增 `MemoryRepositories.init(context)` 统一入口（内部 init 长期 + 短期），`EtaApp` 只调这一处。
- 防再漏：新增测试验证 init 后两个仓库均可读写（Robolectric）。
- 顺带消除两个同名类的混淆入口。

### S2 UI 保存并发保护（P0）
- `replaceAllIfRevision` 上提到 `MemoryRepository` 接口（现只在实现类上）。
- UI `saveMemory` 改走乐观锁：基准 revision = 进入页面/上次加载时的 revision；
  冲突 → 不覆盖，notice「记忆已被更新，已为你载入最新内容」+ 自动刷新。
- Agent 侧写入本就有乐观锁，不动。

### S3 历史快照与回滚（旗舰能力）
- 任何成功写入（UI 保存 / Agent mutate / 整理追加 / 恢复）**落盘前**自动留快照：
  `memory/history/<yyyyMMdd-HHmmss>-<rev8>.md`，上限 10 份 FIFO 滚动删除。
- 新 API：`listHistory()` / `restoreHistory(id)`；恢复本身先给当前内容留快照（回滚也可回滚）。
- UI：文档卡头部加「历史」入口 → 对话框列表（时间 + 大小）→ 单条恢复（二次确认）。
- 历史不进全局备份（本地安全网，备份本身已是快照）。

### S4 备份完整性（P0 数据安全）
- 备份格式加 `memoryShortMd` 字段（可空，旧备份无此字段 → 恢复时跳过，向后兼容）。
- 恢复动作：长期+短期都恢复；恢复前对话框明确「将覆盖现有记忆」。

### S5 整理（rollup）结果预览确认
- 拆分 `MemoryRollup`：`propose()`（LLM 提炼，只返回文本）/ `commit(text)`（revision 乐观锁追加）。
- 流程变为：点整理 → （有草稿则确认丢弃）→ LLM 提炼 → **结果预览对话框**（可滚动全文）→
  「追加到长期」/「放弃」→ 落盘。杜绝未经审视的 LLM 输出直接污染长期记忆。

### S6 一致性与工程杂项
- 标题口径统一 **1-3 级**：注入索引 `^#{1,2}` → `^#{1,3}`（与查重一致；解析保持 1-6 容错）。
- `recordOwnWrite` 仅在 `target=long`（缺省）时更新 pin，短期写入不再污染长期注入快照。
- `isEnabled` 缓存化（`@Volatile`，init/setEnabled 时更新），工具派发路径去掉 `runBlocking`。
- 删除 `enabledFlow()` 死代码；freshness 评估显式传 `today`（兑现可测试设计）。
- `safeTake`、`CORE_HEADING` 收敛为单一来源（`agent/memory/MemoryText.kt`）。

## 四、记忆页 UI 小优化

| # | 项 | 说明 |
|---|----|------|
| U1 | 修对话框嵌套 bug | 「整理」对话框移出切换目标的 `let` 块，各自独立显隐（修 B） |
| U2 | 字节/脏标记实时化 | 击键即时乐观更新；300ms 防抖后在 IO 线程精确重算字节与「是否真的改了」（改回原样 → 保存栏自动消失）。消除 C 的失效窗口，又不回到每键全量比对的卡顿 |
| U3 | 忙碌状态分离 | 新 `isRollingUp` 独立字段；整理期间保存栏不再误显「保存中」 |
| U4 | 搜索命中计数+高亮 | 显示「N 处命中」；过滤视图中匹配文本加粗高亮 |
| U5 | 大纲直达 | 文档卡下加 1-3 级标题 chips（横向滚动）：点选=只看该节（全文口径，归档区也直达，解决「归档区只能进编辑看」）；再点/「全部」还原 |
| U6 | 核心注入水位 | 设置区显示 `已用/预算` 字符条（复用注入链路同一算法），>80% 变色警示——把「89% 无人知晓」的事故面做成一眼可见 |

## 五、实施顺序（每步独立可验收）

1. S1 初始化断线 + 测试锚点
2. U1 对话框 bug
3. S2 保存乐观锁 + U2/U3 状态诚实化（同一片 Store 代码）
4. S3 历史快照与回滚（数据层 → UI）
5. S4 备份完整性
6. S5 rollup 拆分 + 预览确认
7. S6 杂项一致性
8. U4/U5/U6 导航与可视化
9. 全量测试、CI 绿、文档与版本记录

## 六、测试策略

- 新增：`MemoryRepositories` 初始化测试（防 A 复发）、历史快照（建/限/滚动/恢复/恢复前留档）、
  UI 保存冲突路径、dirty 防抖清脏、rollup propose/commit 拆分、备份短长期回环、索引 1-3 级。
- 更新：`AgentMemoryContextBuilderTest`（索引口径）、`MemoryRollupTest`（拆分后 API）、
  `AgentMemoryStoreTest`（历史行为）。
- 验收：CI 双 job 全绿（基线 1795 例 + 新增），0 失败。

## 七、边界（本轮不做）

- 工具 schema 不变（无 `memory_history` 等新工具）——保住与上游的合并健康度。
- 不做自动整理（rollup 仍手动触发）、不做每会话自动捕获、不做记忆导入导出 UI。
- 注入预算算法（window/16 钳位）不动；`ConversationMemoryPins` 前缀缓存机制不动。
- 数据层 `AgentMemoryStore` 改名（与 UI 层同名类区分）暂缓——牵动测试面大，收益是可读性。

---

## 八、落地状态（2026-10-22 实施后回填）

全部条目已实施，与上文方案一一对应：

| 方案项 | 状态 | 备注 |
|---|---|---|
| S1 初始化断线 | ✅ | `MemoryRepositories.init` 单入口 + `MemoryRepositoriesInitTest` 防复发 |
| S2 保存乐观锁 | ✅ | `replaceAllIfRevision` 上接口；冲突→提示+载入最新（`AgentMemoryStoreStateTest`） |
| S3 历史快照 | ✅ | 写前留档 FIFO 10 份、恢复可回滚、id 防路径穿越（`MemoryHistoryTest` 7 例） |
| S4 备份完整性 | ✅ | schema v4 携带 `memoryShortMd`，旧备份跳过；恢复失败补偿回滚短期（备份回环用例） |
| S5 rollup 预览确认 | ✅ | `propose/commit` 拆分（`MemoryRollupTest` 2 新例），确认后才落盘 |
| S6 一致性杂项 | ✅ | 标题索引 1-3、recordOwnWrite 仅长期、isEnabled 缓存化去 runBlocking、删 enabledFlow、MemoryText 收敛 |
| U1 对话框嵌套 bug | ✅ | 「整理」确认独立显隐 |
| U2 字节/脏实时化 | ✅ | 300ms 防抖精确重算（可注入参数） |
| U3 busy 分离 | ✅ | `isRollingUp` 独立 |
| U4 搜索命中+高亮 | ✅ | 命中计数 + 匹配加粗（query 含 Markdown 控制字符时不高亮） |
| U5 大纲直达 | ✅ | `AgentMemoryPreview.headings/section` 纯函数 + chips（`AgentMemoryPreviewOutlineTest`） |
| U6 注入水位 | ✅ | 已用/预算条，>80% 与截断转警示色 |

测试锚点：新增 5 个测试类 + 3 个既有测试类扩展。Windows 本地文件 IO 类用例按平台约定跳过
（`PlatformAssumptions.requireRenameOverwrite`），CI（Linux）执行全量。
