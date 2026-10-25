# 上下文压缩完整性升级（context-compaction-v2）

> 2026-10-25 · 目标：把压缩从「能压」升级为 **压得全、压得准、看得见、压得省**。
> 基线：当前栈已有校准触发线（0.8×窗口）、硬裁剪兜底、压缩熔断、锚点逐字保留、
> 摘要节校验、多代检查点（KEEP_RECENT_CHECKPOINTS=1）、planOverview 注入。

## 现状审计（升级依据）

| # | 空白 | 现状证据 |
|---|---|---|
| 1 | 工具结果没瘦身就进摘要 | `AgentToolResultPruner` 只做请求级修剪（2k 阈值）；摘要输入靠 32k..512k 预算截断，模型看不到全貌 |
| 2 | 摘要只校验「节」不校验「内容」 | `AgentContextSummarizer` 有节校验+重试，但锚点/约束/标识符丢失仍可通过 |
| 3 | 硬裁剪纯时间序 | `AgentHistoryTrimmer` 丢老留新；3 轮前的任务约束随大工具输出一起被丢 |
| 4 | 只在到线时压 | `before >= trigger` 才压；单轮暴涨仍靠硬裁剪兜底 |
| 5 | 压缩不可见 | 事件有 before/after，UI 无时间线/摘要预览/编辑 |
| 6 | 缓存不感知 | 压缩重写摘要后缓存全失效，尾部保留未明示锁定 |

## 工作流

### W1 工具结果瘦身（摘要入口处）
- 新 `AgentToolResultSlimmer`：`slim(messages, keepRecentTurns = 2)`
  - 近期轮（默认 2 轮）工具结果原样；
  - 更旧的工具结果 → 结构化瘦形：工具名、成败、结论首 2 行、关键行 ≤8、原字符数
  - **保持 role/tool_call_id 链接与 `_eta_*` 键**，不破坏 `compactEnd` 的批次边界判定
- 接入点：`AgentContextCompactor.compact` 构建 `source` 前调用
- 验收：瘦身比、近期不动、链接不断、空/异常结果不炸（单测）

### W2 内容级自检 + 重要性裁剪
- W2a `AgentContextSummarizer`：压缩前从 source 提取关键事实候选
  （锚点句、绝对路径/URL/长 ID、含「必须/不要/记住/禁止」的约束句），
  摘要后校验命中率；不足带缺失项提示重试一次；重试仍不合格 → 接受首版（与节校验同哲学：缺一节好过压缩失败）
- W2b `AgentHistoryTrimmer`：保留池分层
  必留 = 首条用户消息 + 最近 K 条 + 含高价值标记的消息（约束句/路径/URL/ID）；
  其余时间序丢弃直到预算内。接口兼容（`JsonOutcome` 不变，Outcome 增 retainedByImportance）
- 验收：约束不被丢、大工具输出先丢、预算满足、签名回合仍禁裁（单测）

### W3 压缩历史面板
- 数据：复用会话 journal 的压缩标记；不足则 conversationState 增 `compactionLog`
  （时间、before/after、dropped、摘要文本），旧数据缺省空（备份兼容，参照 memoryShortMd 套路）
- UI：上下文面板 = 时间线 + 摘要预览/编辑 + 被折叠计数（体验对齐记忆页）
- 验收：记录追加/序列化兼容（单测）；真机可见可编辑

### W4 预测触发 + 缓存边界
- W4a `AgentContextSession`：记录最近轮增长；`before + max(recentGrowth, outputReserve) >= window`
  即提前 compact（reasonCode = PROACTIVE），避免单轮暴涨靠硬裁剪
- W4b 压缩产出尾部保留 `CACHE_TAIL_TURNS`（默认 2 轮）原样，摘要只压前缀；
  明示注释 + 测试锁定（未来接缓存指标可量化）
- 验收：预测触发条件、尾部不动（单测）

## 不做（明确出界）
- 不加价格表/费用估算（独立需求）
- 不做 ML 语义选择（重要性用规则启发式，可解释可测）
- 不动 schema（若 W3 走 conversationState 载荷，走 JSON 字段扩展而非 Room 迁移）

## 验证与发布
- 每 W 一个测试类；WSL 全量门（`tools/wsl-test.sh`）
- 真机冒烟：长会话触发压缩 → 面板可见 → 压后对话正常
- 发版：4.0.2（功能版，versionCode 递增）
