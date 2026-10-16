# 记忆板块升级设计文档（spec：memory-upgrade）

状态：**v2 完整落地方案（四机制合并实施）**
版本：2026-10-15
关联：v3.11.26 之后的功能规划

## 1. 背景与目标

对当前记忆系统做一次**一次性完整升级**，合并落地四个小机制（均低/中风险、有真实增量价值）：

1. **短期/长期分层** —— 补时间维度，任务结束可清短期不影响长期
2. **rollup 整理**（手动触发） —— 防记忆膨胀，把短期稳定事实提炼进长期
3. **记忆中心 UI 增强** —— 分节导航/搜索/按节编辑
4. **会话热力图** —— 纯展示组件，提升记忆/统计页可读性

**明确不做**（与 v1 相同）：不引入 embedding/向量库、不移植 mem0 结构化、记忆不移出 system prompt、不加自动定时 rollup（手动先行）。

## 2. 落地关键事实（已核实）

- `AgentMemoryStore(root)` 构造只需 root 目录——**参数化复用已有先例**（`CharacterMemoryRepository` 每角色一个 store：`AgentMemoryRepository.kt:45-48` 的 `stores.getOrPut(root.path) { AgentMemoryStore(root) }`）
- `memory_write` schema：required = `mode` + `revision`（`AgentMemoryToolCatalog.kt:124`），加 `target` 参数需改 schema + 实现
- 记忆中心 UI：`AgentMemoryScreen`（整页 Markdown 预览/编辑 + 清空确认），分节导航/搜索/按节编辑为新增
- 热力图数据源：会话表 `createdAt`（`AgentConversationStore`），按天计数——OmniBot 逻辑可照搬

## 3. 实施设计（一次落地，分步提交）

### 3.1 存储层：短期文件（机制 1 的地基）

**新增 `ShortMemoryRepository`**（照抄 `CharacterMemoryRepository` 模式）：
- 路径：`filesDir/memory-short/MEMORY.md`（与长期 `filesDir/memory/MEMORY.md` 完全隔离）
- 复用：`AgentMemoryStore`（同一套 revision 锁 / AtomicFile / 1 MiB 上限 / 3500 字符单写）
- 语义：短期记忆 = 当前任务/会话的临时事实，**不进核心注入**（只进标题索引或不进），按需 `memory_get` 读
- 新增 `MemoryTarget` 枚举（LONG / SHORT），`AgentMemoryRepository` 与 `ShortMemoryRepository` 统一暴露

**工具层**：
- `memory_write` 加 `target` 参数（enum：long|short，**默认 long 向后兼容**）——改 `AgentMemoryToolCatalog.kt:56-124` schema + `AgentLocalTools.memoryWrite` 路由到对应 store
- `memory_get` 加 `target` 参数（默认 long）——读对应 store
- revision 各自独立（两文件各自 SHA-256）

**守卫适配**：
- `ConversationMemoryPins` 只固定长期核心注入（短期不进注入 → 无缓存适配，防缓存击穿）
- 开关：沿用总记忆开关（关闭 → 两 store 都不注入/不暴露）
- 角色记忆隔离不变（角色走独立路径，`target` 不影响）

### 3.2 rollup 整理（机制 2，手动触发）

**新增 `MemoryRollup`（复用 `AgentContextSummarizer` 调用模式）**：
- 入口：记忆页设置区"整理记忆"按钮（手动，无定时）
- 输入：短期 store 全文（≤3500 字符分段，超限取最近段）
- 调用：LLM 单次（`ProviderRequestPurpose.COMPACTION` 复用，prompt 要求输出去重/合并后的长期候选）
- 输出处理：
  - 校验非空、≤3500 字符
  - 追加到长期 store（`mutate` append，revision 锁）
  - 可选：追加成功后短期 store `clear`（需确认，默认**不清**——先保留，用户手动清）
- 失败：不写任何文件（先读后写 + revision 锁；LLM 失败保留原文）
- 成本：一次 LLM 调用/次；`CONTEXT_SUMMARY_INVALID` 类错误码复用

### 3.3 记忆中心 UI 增强（机制 3）

`AgentMemoryScreen` 增补（不替换现有整页编辑）：
- **分节导航**：按 `#` 标题列出（复用 `headings()` 逻辑），点击定位/进入该节编辑
- **搜索框**：跨节过滤（复用 `memory_get` 的 query 语义，UI 侧过滤，不新增工具）
- **按节编辑**：选中标题 → 只编辑该节区间（`replace_range` 语义的 UI 化）；未选中时保留整页编辑
- 短期记忆入口：`MemoryTarget.SHORT` 切换（Tab 或下拉），同套 UI 展示短期 store

### 3.4 会话热力图（机制 4）

**新增 `ui/components/ConversationHeatmap.kt`**（照 OmniBot 逻辑）：
- 数据：`AgentConversationStore.load(context).conversationsById` 各会话 `createdAt` 按天计数
- 16 周网格、0-4 强度分桶（0、1、2-3、4-6、>6）、连续天数 streak、月份/周轴标
- 入口：记忆页顶部 或 用法统计页（复用现有统计入口，优先用法统计页——与"会话活跃度"语义更贴）

## 4. 分步提交与验证

| 步 | 内容 | 依赖 | 验证 |
|---|---|---|---|
| 4.1 | 存储层：`ShortMemoryRepository` + `MemoryTarget` + `memory_write/get` target 参数 | 无 | 新增 store 单测（复用 AgentMemoryStoreTest 模式）+ 工具 schema 测试 |
| 4.2 | 会话热力图组件 + 入口 | 无（可与 4.1 并行） | 组件单测（数据聚合/分桶）+ UI 冒烟 |
| 4.3 | 记忆中心 UI 增强（分节导航/搜索/按节编辑/短期切换） | 4.1 | UI 测试（Robolectric）+ 门禁 |
| 4.4 | rollup 整理（按钮 + LLM 调用 + 追加） | 4.1 | rollup 单测（LLM 注入 + 追加校验 + 失败保留） |
| 4.5 | 全量门禁 + CI 双绿 + 发版 | 全部 | 与历次发布同流程 |

**并行**：4.1 与 4.2 无依赖可并行；4.3/4.4 依赖 4.1。

## 5. 兼容性与回滚

- 旧数据零影响：长期 `MEMORY.md` 格式不变；短期是新文件，不存在即空
- `memory_write`/`memory_get` 无 `target` 时默认 long（旧调用向后兼容）
- 每个分步独立提交，4.5 统一发版；若某步回归，回退该提交不影响其他步

## 6. 风险与缓解

| 风险 | 缓解 |
|---|---|
| 短期/长期混淆 | 工具 schema 显式 target + 描述明示语义；UI 分 Tab 隔离 |
| rollup 写坏记忆 | revision 锁 + 先读后写 + 输出校验；失败保留原文 |
| 核心注入缓存击穿 | 短期不进 `ConversationMemoryPins`，长期注入逻辑零改动 |
| UI 改坏现有编辑 | 新增模式，整页编辑保留；门禁 + CI |
| 热力图数据量大 | 按天聚合一次，16 周上限；不在主线程 |

## 7. 不做清单（防蔓延）

- ❌ embedding/向量库/语义检索
- ❌ mem0 结构化字段（score/relations/metadata）
- ❌ 记忆移出 system prompt
- ❌ 自动定时 rollup（手动先行，定时版待数据）
- ❌ 记忆自动写入（仍由模型工具/用户显式触发）
- ❌ 多设备同步/云端记忆
