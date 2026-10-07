# Spec: 上下文成本护栏（context-cost-guard）

状态：已实现并发布（v3.8.6，commit c899528；CI run 37563252104 全绿），待真机复验 input 峰值
模块 id：`context-cost-guard`

## 背景与证据（真机）

2026-10-07 会话 `conv-3d2f1ce4`（标题 XunEr-agent）额度瞬间耗尽并报 HTTP 402。DB（`eta.db`）实测：

- 消息 4253 条 / 工具调用 1723 次 / 用户回合 96 / 助手回复 813 轮
- 813 轮累计 `input_tokens` = **289,405,760**（2.89 亿），`cached_tokens` = 280,703,552（**命中率 97.0%**）
- 单轮 input 峰值 **750,594**；压缩后基线 61,945 ~ 73,252
- 压缩事件 7 次；前三次干净：`749,741→61,945`、`469,457→69,091`、`639,094→63,872`
- 末段：`4233` 压缩后不降反升（`517,008 → 748,594`，且该轮 cache 命中仅 **9.4%**，等于按全价重算），随后 `4244 / 4248 / 4252` 连续压缩，夹着 `4242 / 4250` 两次 `runtime_failed`（402）
- 按 DeepSeek 口径（命中 ≈ 1/10 价）折算：未命中仅占 3% 的量，却贡献 **23.7%** 的费用

## 根因（本次修复 R1-R3）

| # | 问题 | 位置 |
|---|---|---|
| R1 | 触发线 = `0.75 × window`（100 万 → 75 万），大窗口模型上等于无护栏；窗口配得越大反而越危险 | `AgentContextBudget.TRIGGER_RATIO`、`AgentContextSession.compact` |
| R2 | 压缩失败无退避/熔断：失败分支不重置 `inputTokens`，而 `AgentLoop:107` 每轮 loop 都调用 `compact()` → 失败后每轮重试一次全量摘要请求 | `AgentContextSession.compact` 失败路径 |
| R3 | 压缩结果只比较字符串长度，可能压完仍高于触发线 → 下一轮立刻再压（抖动即重复计费 + 反复击穿缓存） | `AgentContextCompactor.compact` 末尾校验 |

## 不在本次范围（已识别，另立任务）

- R4 摘要请求输入截断 / 独立廉价摘要模型（`AgentContextSummarizer` 目前全量发主模型）
- R5 成本口径记账（`miss + 0.1 × hit`）与 UI 可视（会话累计、压缩次数、降幅预警）
- R6 硬裁剪 `TARGET_RATIO = 0.95` 与触发线之间的"无机制地带"
- 上游上报（R1-R3 核心逻辑源自上游 Mangi-11/Eta）

## Objective

让长会话不再因上下文规模失控而产生不可预期的费用爆炸，且**压缩本身必须"划算"**：要么显著降低占用，要么不压。

## 行为定义（验收语义）

1. **触发线加上限**：`triggerTokens(window) = min(floor(window × 0.75), 200_000)`；窗口为空或 ≤ 0 时无触发。压缩与相关判据统一走该函数。
2. **失败熔断**：非 `force` 的压缩尝试一旦失败，同一 run 内不再重试压缩（改为依赖既有硬裁剪兜底）；`force = true`（用户手动压缩 / run 收尾）不受熔断限制，但失败同样置位熔断。
3. **降幅门槛**：压缩产物估算若仍 **高于触发线**，按 `CONTEXT_NO_REDUCTION` 处理并保留原文——避免"压完立刻再压"的抖动。

## Tech Stack

Kotlin / Android（现有工程），JUnit 单测；无新增依赖。

## Commands

```
单测（本模块相关）：ANDROID_HOME=/workspace/android-sdk GRADLE_USER_HOME=/workspace/.gradle-home \
  ./gradlew :app:testDebugUnitTest --tests "*Context*"
全量单测：同上，去掉 --tests
打包/发版：走 CI（.github/workflows/android-release.yml），本地不产出 APK
```

## Project Structure

```
app/src/main/kotlin/io/github/mangi/eta/agent/model/   → 上下文预算/压缩/裁剪（本次改动区）
app/src/test/kotlin/io/github/mangi/eta/agent/model/   → 对应单测
docs/specs/                                            → 本 spec
docs/reviews/                                          → 审查与取证留档
```

## Code Style

沿用现有风格：内部类 + 常量集中在 `companion object`，注释说明"为什么"而非"做什么"。

```kotlin
/** 触发线的绝对上限：窗口越大，比例阈值越容易让单轮成本失控。 */
const val ABSOLUTE_TRIGGER_CAP = 200_000

fun triggerTokens(window: Int?): Int? =
    window?.takeIf { it > 0 }?.let { minOf((it * TRIGGER_RATIO).toInt(), ABSOLUTE_TRIGGER_CAP) }
```

## Testing Strategy

JUnit4 单测，覆盖三处行为；不依赖真机与网络。判据偏向"可断言的行为"，不锁实现细节。

## Boundaries

- Always：先写测试再改实现；保持现有公开语义（`compact` 调用方签名不变）；改动只落在 model 包内。
- Ask first：改 UI、改 CI、加依赖、调整 `TARGET_RATIO`、调整 `ABSOLUTE_TRIGGER_CAP` 取值。
- Never：动工具能力目录/工具契约；删除既有失败事件与可观测性；顺手改无关文件。

## Success Criteria

- [ ] `triggerTokens(1_000_000) == 200_000`，`triggerTokens(256_000) == 192_000`（有单测）
- [ ] 模拟摘要连续失败：同一 run 内 `summarize` 只被调用 1 次；`force` 仍可调用
- [ ] 压缩产物高于触发线时抛 `CONTEXT_NO_REDUCTION` 且保留原始 messages
- [ ] 全量单测与 CI 全绿
- [ ] 真机：新会话长任务中 `conversation_messages.input_tokens` 峰值 ≤ 约 20 万（不含首轮系统提示）

## Open Questions（无异议则按默认执行）

1. `ABSOLUTE_TRIGGER_CAP = 200_000` —— 默认 20 万（约为 10 万级 system+工具 schema 的 2 倍余量）。可调。
2. 熔断粒度 —— 默认"本 run 内不再重试"，跨 run 自动恢复（不落盘状态）。
3. R4/R5/R6 —— 本次不做，另立任务。
