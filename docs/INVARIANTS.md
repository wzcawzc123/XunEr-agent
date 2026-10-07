# 不变量清单（F1–F8）

目的：本 fork 已落地的 8 项真修复（F1–F8）在合并上游或后续改动时**不得被冲掉**。
每一项必须能一对一映射到源码锚点与测试；新增相关改动时先更新本表。

状态标记：✅ 有测试锁 · ⚠️ 部分覆盖（已知缺口见文末）。

| ID | 不变量 | 源码锚点 | 测试 | 状态 |
|----|--------|----------|------|------|
| F1 | 坐标点击必须过新鲜度检查：滚动\/切窗后旧坐标返回 `STALE_COORDINATE`，禁止静默重放 | `agent\/tool\/CoordinateFreshnessPolicy.kt`；`agent\/tool\/AgentLocalTools.kt`（observe 发布 \/ tap 校验）；scroll\/swipe 递增 `contentVersion` | `CoordinateFreshnessPolicyTest` | ✅ |
| F2 | 分辨率切换（FHD↔2K）后旧坐标返回 `STALE_RESOLUTION`，判据取 observe 时记录的 screen 尺寸 | 同上 + `screenDimensions()` | `CoordinateFreshnessPolicyTest`（staleResolution 用例） | ✅ |
| F3 | 超窗 history 不得导致整 run 失败：先摘要压缩，硬裁剪 `AgentHistoryTrimmer` 只在 tool 边界裁 | `agent\/model\/AgentHistoryTrimmer.kt`、`AgentContextSession.kt`、`AgentContextCompactor.kt`、`AgentLoop.kt` | `AgentHistoryTrimmerTest`、`AgentContextCompactionTest`、`AgentContextRecoveryTest` | ✅ |
| F4 | 脱敏占位参数（`_redacted`\/`_note`\/`_fields`）被模型原样重发时，返回可执行引导并标记 `REDACTED_ARGUMENTS_REPLAYED`，不得报“缺字段”、不得死循环 | `agent\/model\/AgentToolCallValidator.kt`（`isRedactedReplay`）；`AgentLoop.kt`（code 映射） | `AgentToolCallValidatorTest.redactedPayloadIsGuidedInsteadOfReportedAsMissingField` | ⚠️ 见缺口① |
| F5 | `memory_get` 分页必须给出 `next_start_line` + `paging_hint`；末页停止提示；query 结果不带分页字段 | `agent\/tool\/AgentLocalTools.kt`（memory 分发） | `AgentMemoryGetPagingTest`（3 例） | ✅ |
| F6 | 核心记忆按 heading 分段**全量合并**注入，超预算按行截断（UTF-16 安全）并报告续读，不得只取第一段 | `agent\/memory\/AgentMemoryContextBuilder.kt` | `AgentMemoryContextBuilderTest.everyCoreHeadingSectionIsMergedIntoTheInjectedCore` 等 6 例 | ✅ |
| F7 | `read_image` 结果必须携带 `image_width`\/`image_height`（原图像素尺寸）与“与 screen 不一致禁止推算坐标”提示 | `agent\/tool\/AgentImageTools.kt` | `AgentImageToolsTest.readImageReportsPixelDimensionsAndCoordinateWarning`（Robolectric，CI 侧验证） | ✅ |
| F8 | 上下文成本护栏：压缩触发线 = `min(窗口×0.75, 200_000)`；压缩失败即熔断（本 run 内不再重试非强制压缩，`force` 不受限）；压缩降幅不足 20% 判 `CONTEXT_NO_REDUCTION` 且不得改写上下文；熔断后硬裁剪线降到触发线 | `agent\/model\/AgentContextBudget.kt`（`triggerTokens`\/`ABSOLUTE_TRIGGER_CAP`）、`AgentContextSession.kt`（`compactionBlocked`\/`compactionBlockedForRun`）、`AgentContextCompactor.kt`（`MIN_REDUCTION_PERCENT`）、`AgentLoop.kt`（熔断后 `trimWindow`） | `AgentContextCostGuardTest`（5 例，含 `failedCompactionFallsBackToTrimAtTriggerLine`）+ `AgentContextRecoveryTest`（触发线阈值用例） | ✅ |

## 已知缺口（如实记录，不假装覆盖）

1. **F4 Loop 层 code 映射无直接测试**：`AgentLoop.executeTool` 把校验失败按
   `isRedactedReplay` 映射到 `REDACTED_ARGUMENTS_REPLAYED` 这一分支未被单测直接覆盖
   （validator 层已锁）。Loop 级测试需要完整 run 脚手架，收益\/成本比低，暂记缺口。
2. **点击后结果校验缺失**：坐标点击是否命中目标，当前只靠模型观察确认
   （`ACTION_OUTCOME_UNKNOWN` 仅覆盖“无法确认”场景）。结构化 locate\/命中校验
   属于 M2（见修复方案），不在本表范围内。
3. **F8 的成本侧未覆盖**：护栏只约束「占用与压缩行为」，尚无 `miss + 0.1×hit` 成本口径
   记账与 UI 可视（spec `docs/specs/context-cost-guard.md` 的 R5），也无摘要输入截断（R4）、
   硬裁剪 `TARGET_RATIO=0.95` 与触发线之间空档（R6）的处理。真机复验（长会话 input 峰值 ≤ 20 万）待新版本装机后进行。

## 测试门禁（验收规则）

- **本地（aarch64 Linux）**：Robolectric 全部失败属环境限制（“native runtime is not
  supported on Linux (aarch64)”），基线见 `\/workspace\/baseline-test-failures.txt`（249 条）。
  本地验收 = **新增非环境失败相对基线为 0**，禁止以“全绿”为目标（永远达不到）。
- **环境失败类别**：Robolectric native runtime（`DefaultNativeRuntimeLoader`）、DexKit
  （libdexkit.so 初始化）、conscrypt、`canWrite` 文件权限。
  **归类看失败消息类别，不看是否在基线文件中**（基线文件非全量，例如
  `AgentMemoryGetPagingTest` 三个 Robolectric 用例环境失败但未被基线收录）。
  出现环境类别之外的新失败，先归因再下结论。
- **CI（ubuntu-latest，android-release.yml `:app:testDebugUnitTest`）**：Robolectric
  可用，是 Robolectric 类测试（含 F7）的唯一验证点；发布流水线跑测试，测试失败即阻断发布。
  fork 仓库的 Actions 为**手动 dispatch**（`gh workflow run "Eta Build" -R wzcawzc123/XunEr-agent --ref main`），
  push 不自动触发。
- **本地构建前提**：必须加 `-Pandroid.aapt2FromMavenOverride=\/workspace\/tools\/aapt2-qemu\/aapt2`
  （设备 aarch64，AGP 自带 aapt2 为 x86_64，否则 `AAPT2 Daemon startup failed`）。

## Changelog 分类规范

发布说明中的每一条改动必须归入且只归入一类：

- **逻辑修复**：改了代码行为\/契约计算（可测不变量变化）——可称“根因修复”。
- **策略调参**：改常量\/阈值\/降级顺序——写清触发条件，不称“根因”。
- **prompt 约束**：只改提示词\/引导文案——**禁止**写成“根因修复”，须标注为引导层
  （引导层可被模型忽略，其有效性需真机反馈验证，例如 v3.4.7–v3.4.9 的坐标引导
  在 2026-10-04 真机取证中表现为“升级逻辑触发但首中率未改善”）。
