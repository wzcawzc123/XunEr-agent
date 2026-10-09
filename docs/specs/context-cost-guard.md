# Spec: 上下文成本护栏（context-cost-guard）

状态：R1-R3（v3.8.6）、R6（v3.8.7）、R7 阈值对齐 harness + 逐字保留 + 需求锚点 + 结构化摘要（v3.9.0）均已实现并发布，CI 全绿
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
- R6 硬裁剪 `TARGET_RATIO = 0.95` 与触发线之间的"无机制地带" → **已在 v3.8.7 实现**（熔断后裁剪线降到触发线，见行为定义 4）
- 上游上报（R1-R3 核心逻辑源自上游 Mangi-11/Eta）

## Objective

让长会话不再因上下文规模失控而产生不可预期的费用爆炸，且**压缩本身必须"划算"**：要么显著降低占用，要么不压。

## 行为定义（验收语义）

1. **触发线（harness 口径，v3.9.0）**：`triggerTokens(W) = min(floor(W × 0.8), W − 输出预留 − 余量)`；输出预留与余量在小窗口下收缩（`min(16384, W/16)`、`min(65536, W/8)`）。100 万窗口下 ≈ **838,860**。
2. **逐字保留尾部（v3.9.0）**：压缩时按 `retainTokens(W) = (W − 输出预留) × 0.16` 从末尾逐字保留近期历史（100 万窗口 ≈ 16.5 万）；压缩"少而狠"以降低压缩次数、摘要漂移与缓存击穿。
3. **需求锚点（v3.9.0）**：会话首条用户消息（或既有摘要里的锚点段）逐字带过每一代摘要，标记 `[原始任务（逐字保留，勿改写）]`。
4. **结构化摘要（v3.9.0）**：固定 8 小节（原始需求/技术概念/文件与代码/错误与修复/待办/当前工作/下一步/关键上下文）+ 明确"合并旧摘要、丢弃过期信息"。
5. **失败熔断（v3.8.6）**：非 `force` 的压缩尝试一次失败即停（本 run 内），`force` 不受限。
6. **降幅门槛（v3.8.6）**：压缩产物 ≥ 压缩前的 80% 判 `CONTEXT_NO_REDUCTION`，保留原文。
7. **熔断期兜底（v3.8.7）**：熔断后硬裁剪线由窗口降到触发线。
8. **多代检查点与摘要长度纪律（v3.9.1）**：最近一代检查点原样保留、不参与重写（更早的才合并进新摘要）；摘要上限 12,000 → 16,000 字符并要求紧凑要点，避免「摘要超长 → 判失败 → 熔断压缩」。

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
/** 触发线的绝对上限：0 = 不设上限（当前定版，纯比例，与 harness 行为一致）。 */
const val ABSOLUTE_TRIGGER_CAP = 0

fun triggerTokens(window: Int?): Int? =
    window?.takeIf { it > 0 }?.let {
        minOf((it * TRIGGER_RATIO).toInt(), it - outputReserveTokens(it) - headroomTokens(it))
    }
```

## Testing Strategy

JUnit4 单测，覆盖三处行为；不依赖真机与网络。判据偏向"可断言的行为"，不锁实现细节。

## Boundaries

- Always：先写测试再改实现；保持现有公开语义（`compact` 调用方签名不变）；改动只落在 model 包内。
- Ask first：改 UI、改 CI、加依赖、调整 `TARGET_RATIO`、调整 `ABSOLUTE_TRIGGER_CAP` 取值。
- Never：动工具能力目录/工具契约；删除既有失败事件与可观测性；顺手改无关文件。

## Success Criteria

- [x] `triggerTokens(1_000_000) == 800_000`，`triggerTokens(256_000) == 204_800`（有单测；`ABSOLUTE_TRIGGER_CAP=0` 不设上限）
- [ ] 模拟摘要连续失败：同一 run 内 `summarize` 只被调用 1 次；`force` 仍可调用
- [ ] 压缩产物高于触发线时抛 `CONTEXT_NO_REDUCTION` 且保留原始 messages
- [ ] 全量单测与 CI 全绿
- [ ] 真机：新会话长任务中 `conversation_messages.input_tokens` 峰值 ≤ 约 20 万（不含首轮系统提示）

## Open Questions（无异议则按默认执行）

1. `ABSOLUTE_TRIGGER_CAP = 0` —— v3.9.0 定版为不设上限（纯比例，与 harness 一致；早期曾拟 20 万绝对上限，后因小窗口模型反而受压弃用）。如未来引入请求级输出上限再评估。
2. 熔断粒度 —— 默认"本 run 内不再重试"，跨 run 自动恢复（不落盘状态）。
3. R4（摘要输入截断）已落地；R5（成本记账/UI 可视）与 R6（硬裁剪与触发线之间空档）另立任务。
