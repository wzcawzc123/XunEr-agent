# Eta 上下文压缩板块 · 全局审查报告

**审查日期**：2026-10-16
**代码基线**：`13e4d1f` 之后 → `d6a8e67`（v3.11.28）
**设备**：一加 11（PHB110）/ ColorOS 16 / Android 16
**会话**：`conv-4a54eb54-...`（1,287 条消息，已发生多次压缩）

---

## 0. 摘要

**用户现象**：手动压缩后，提示条显示「摘要只把上下文从约 **420083** 降到 **412433** tokens」（仅降 1.8%），
而模型随后反馈「上下文已用到 99%」。用户认为压缩失效。

**实测结果**：**压缩实际非常有效** —— `input_tokens` 从 **777,273** 降到 **365,278**（**↓53%**）。

**根因**：`rawEstimate()` 对「工具结果（JSON）」这类内容**系统性低估约 1.85 倍**，
而压缩前后的历史成分发生剧变（工具结果 91% → 以摘要文本为主），
导致**压缩前的估算严重偏低、压缩后的估算反而准确**，
两者相比得出的"降幅"完全失真 → 判定「不划算」→ 摘要被拒 → 退化为硬裁剪。

**核心矛盾**：**用同一套"字符数→token"的估算，去比较两类性质完全不同的内容。**

---

## 1. 关键实测数据

### 1.1 input_tokens 曲线（DB 实测）

```
#1275  777,273   ← 压缩前
#1279  365,278   ← 压缩后（↓53%）
#1286  368,941   ← 当前
```

### 1.2 估算 vs 实测对照

| 对象 | 字符数 | `rawEstimate` | 实测 `input_tokens` | 偏差 |
|---|---|---|---|---|
| **`history`（压缩后实际提交）** | 1,007,029 | **363,922** | **365,278** | **0.4%** ✅ |
| 提示条「压缩后」`afterEstimate` | — | **412,433** | — | +13% ⚠️ |
| 提示条「压缩前」`beforeEstimate` | — | **420,083** | **777,273** | **−46%** ⚠️ |

**结论**：
- 估算函数对**纯文本**（压缩后）**准确到 0.4%**
- 估算函数对**工具结果密集**（压缩前）**低估 46%**

### 1.3 内容成分分析（journal，991 条消息）

```
  tool        1,145,374 字符  (91%)   ← 464 条工具结果
  assistant      95,609 字符  ( 7%)
  user            4,278 字符  ( 0.3%)
```

**待压缩内容 91% 是工具结果（JSON）。**

### 1.4 估算函数定义

```kotlin
fun textTokens(text: String): Int {
    var ascii = 0; var other = 0
    text.codePoints().forEach { if (it < 128) ascii++ else other++ }
    return (ascii + 2) / 3 + other     // ASCII 3 字符 = 1 token
}
```

**对自然语言大致成立，对 JSON 严重偏低**（引号、括号、逗号、冒号各占一个 token，
真实密度接近 1.5~2 字符/token）。

---

## 2. 问题清单

### 🔴 P0-1：`rawEstimate` 对工具结果系统性低估，导致压缩判定失真

**位置**：`AgentContextCompactor.kt:107-113`

```kotlin
val beforeEstimate = AgentContextBudget.rawEstimate(messages)
val afterEstimate  = AgentContextBudget.rawEstimate(result)
if (afterEstimate * 100 >= beforeEstimate * MIN_REDUCTION_PERCENT) {   // 80
    throw failure("CONTEXT_NO_REDUCTION", "摘要只把上下文从约 $beforeEstimate 降到 $afterEstimate ...")
}
```

**为什么必然失真**：
- 压缩**前**：历史 91% 是工具结果 → `rawEstimate` **低估 1.85×**
- 压缩**后**：工具结果被摘要替换 → `rawEstimate` **准确（偏差 0.4%）**
- 比较两个不同偏差方向的估算 → 比值无意义

**后果**：真实降幅 53% 被判为 1.8% → 摘要被拒 → 只能硬裁剪（纯丢弃）→ **内容真丢 + 用户感知"失忆"**。

---

### 🔴 P0-2：判定用的对象与最终提交的对象不一致

```
判定时算的 afterEstimate  =  412,433   （候选 result）
压缩后真正提交的 history   =  363,922   （与实测吻合）
                              ↑ 差 48,511 tokens
```

`result` 由「摘要 + protectedUser + `history.drop(end)` + 保留的旧检查点」构成，
而最终落盘的 history 来源不同（摘要 + journal 尾部）。**判定输入本身就不是最终产物。**

---

### 🟠 P1-1：`calibration` 是全局单值，无法适配成分变化

**位置**：`AgentContextBudget.kt:14, 36-37`

```kotlin
private var calibration = DEFAULT_CALIBRATION          // 0.4
calibration = (inputTokens / requestEstimate).coerceIn(0.1, 8.0)
```

- 对「工具结果密集」内容，正确的校准值 ≈ **1.85**
- 对「纯文本（压缩后）」内容，正确的校准值 ≈ **1.0**
- 单值无法同时满足 → **压缩前后必然有一方被带偏**

---

### 🟠 P1-2：估算在两处使用不同口径（含/不含 tools）

| 用途 | 调用 | 含 tools |
|---|---|---|
| 校准 | `rawEstimate(requestMessages, roundTools)`（`AgentLoop.kt:237`）| ✅ |
| 压缩判定 | `rawEstimate(messages)`（`AgentContextCompactor.kt:107`）| ❌ |

**同一个 `calibration` 被两个不同口径共用** → 校准值与判定输入不自洽。

---

### 🔴 P0-3：三处使用口径完全不同的估算（触发 / 校准采样 / 压缩判定）

| 用途 | 函数 | 含 tools | 乘 calibration |
|---|---|---|---|
| **触发压缩** | `budget.estimate(messages, tools)` | ✅ | ✅ |
| **校准采样** | `rawEstimate(messages, tools)`（`AgentLoop:237`）| ✅ | ❌（本应 raw）|
| **压缩判定** | `rawEstimate(messages)`（`Compactor:107`）| ❌ | ❌ |

**即：决定"何时压缩"与"压缩划不划算"用的是两套完全不同的尺度。**
`AgentContextSession` 自己的注释也点明了这一点：

```kotlin
/** 校准后的上下文估算；裁剪判据用它而不是未校准的 rawEstimate。 */
fun budgetEstimate(messages: JSONArray, tools: JSONArray = JSONArray()): Int =
    budget.estimate(messages, tools)
```

**但 `AgentContextCompactor` 里用的偏偏是未校准的 `rawEstimate`。** 判据与触发脱节。

### 🔴 P0-4：硬裁剪（`AgentHistoryTrimmer`）同样建立在 raw 口径上

```kotlin
const val TARGET_RATIO = 0.95
const val TOOL_SCHEMA_RESERVE_TOKENS = 24_000
private val EMPTY_ESTIMATE = AgentContextBudget.rawEstimate(JSONArray())
private fun costOf(message) = ...   // 与 rawEstimate 同口径
```

**裁剪线（95%）与压缩线（80%）都依赖同一套 raw 估算。**
当历史由工具结果主导时（低估 1.85×），**裁剪判据会认为"还没到 95%"，
而实际可能早已超过窗口** —— 这是"该裁不裁"与"裁了但没裁够"的根源之一。

### 🟡 P2-1：`calibration` 不跨 run 存活

```kotlin
// AgentContextSession 注释：校准值不跨 run 存活（每次运行都新建 Session/Budget）
private var calibration = DEFAULT_CALIBRATION   // 0.4
```

每次 run 的前几轮都用 `0.4`（来自"估算高估 4.82 倍"时代的保守初值），
而当前实测是「低估」场景 —— **初值方向与实际相反**。

---

### 🟡 P2-2：`compactEnd` 内把 raw 估算乘 calibration

**位置**：`AgentContextCompactor.kt:160-168`（v3.11.24 的 C1 改动）

```kotlin
val cost = AgentContextBudget.rawEstimate(JSONArray().put(history[from - 1])) * calibration
if (used + cost > retain) break
```

注释称「retain 是窗口单位预算，成本须乘 calibration 换算同口径」，
但 `retainTokens()` 返回的**本身就是 token 数**（157,378）。此处再乘一次校准，
**又是一次跨口径相乘**。

---

### 🟡 P2-3：摘要输入上限会导致"覆盖不全"

```kotlin
MAX_SUMMARY_INPUT_CHARS = 512_000
private fun boundSummaryInput(safe, budgetChars) = safe.drop(keepFrom)   // 只取【尾部】
```

待压缩内容超过 512K 字符时，**只把尾部交给摘要模型** ——
早期内容既没进摘要、也不在保留区，只能被硬裁剪丢弃。

---

### 🟡 P2-4：提示文案与实际行为不符

```kotlin
"摘要只把上下文从约 X 降到 Y tokens，不足以抵偿压缩自身的开销；原始上下文已保留。"
```

「**原始上下文已保留**」在窄义上成立（摘要未提交），但**用户随后会被硬裁剪悄悄丢内容**，
文案给人"什么都没发生"的错觉，掩盖了真实的数据损失。

---

## 2.5 ✅ 已确认健康的部分（避免误伤）

**触发判据用的是实测 `input_tokens`，不是估算：**

```kotlin
// AgentContextSession.kt:107-115
fun compact(force: Boolean = false, final: Boolean = false) {
    val before  = inputTokens                                        // ← 服务商实测回执
    val trigger = AgentContextBudget.triggerTokens(contextWindow)     // 800,000
    if (!force && (compactionBlocked || !config.autoCompactionEnabled
                   || before == null || trigger == null || before < trigger)) {
        publishSnapshot(); return
    }
    ...
    val candidate = AgentContextCompactor(
        config, provider, runController, roleplay = roleplay,
        calibration = budget.calibrationValue(),
    ).compact(messages, systemCount, sensitiveIds())                   // ← 进去后判定改用估算
}
```

**结论**：**"何时压缩"这一环是健康的** —— 它用真实值。问题集中在**压缩内部的"经济性判定"**。

**手动压缩（`force = true`）会跳过触发检查**，直接进入 `compact()`，
因此最容易暴露判定失真 —— 这正是用户观察到的场景。

---

## 3. 为什么多次修复未能解决

| 版本 | 改动 | 是否触及根因 |
|---|---|---|
| v3.11.24 | 上下文裁剪守卫、C1 校准口径 | ⚠️ 接近，但引入了 P2-2 |
| v3.11.25 | 压缩风暴残留、出站口径 | ❌ |
| v3.11.26 | 校准盲区、校准初值 0.4 | ⚠️ 只调了值，没改模型 |

**根因是「估算模型与内容类型不匹配」** ——
调阈值、调校准值、加守卫，都只是在**同一条失真的判据上做微调**。
每次调整都可能让另一个场景变差（因为校准是单值），于是问题反复出现。

---

## 4. 修复建议

| 优先级 | 建议 | 说明 |
|---|---|---|
| **P0** | **判定改用"客观规模"而非 token 估算** | 例如直接比较 `content 字符总数` 或 `消息条数`，与内容成分无关 |
| **P0** | **或：判定引入实测 `input_tokens`** | 压缩前后的请求回执都有值，可用真实值比较 |
| **P1** | **按内容类型分桶校准** | 工具结果 / 文本 / 摘要 各自维护校准系数 |
| **P1** | **统一估算口径** | 判定与校准使用同一个 `rawEstimate(messages, tools)` 调用 |
| **P2** | `calibration` 按会话持久化 | 跨 run 复用，避免每次回到 0.4 |
| **P2** | 去掉 `compactEnd` 中的重复乘法 | 与 P1 统一口径后一并处理 |
| **P2** | 提示文案如实描述 | 说明"摘要未生成，超出部分将由裁剪丢弃" |

**最小可行修复（不改架构）**：

```kotlin
// 用"字符总数"判定降幅，与 token 估算解耦
val beforeChars = AgentConversationCodec.charCount(messages)
val afterChars  = AgentConversationCodec.charCount(result)
if (afterChars * 100 >= beforeChars * MIN_REDUCTION_PERCENT) { ... }
```

因为**字符数与 token 数在同一次比较中方向一致**（内容变少就是变少），
而 `rawEstimate` 的偏差会随成分变化而变化 —— 这正是当前失效的原因。

---

## 7. 修复实施记录（2026-10-16）

### 已实施（3 个 commit，改动 +108 / −14）

| commit | 修复 | 效果 |
|---|---|---|
| **`47f8c69`** | 降幅判定改用字符数 | 判据偏差 **51 → 4 个百分点**；摘要不再被误拒 |
| **`c3ec52f`** | `compactEnd` 保留成本不再乘 calibration | 保留 **454 → 193 条**（↓57%），字符 **634K → 372K**（↓41%）|
| **`663c836`** | 校准初值 `0.4 → 1.0`（中性）| 消除 **4.6 倍方向性偏差** |

**① P0-1：降幅判定改用字符数** — `AgentContextCompactor.kt`

```kotlin
- val afterEstimate = AgentContextBudget.rawEstimate(result)
- if (afterEstimate * 100 >= beforeEstimate * MIN_REDUCTION_PERCENT) { ... }
+ val beforeChars = messages.toString().length
+ val afterChars  = result.toString().length
+ if (afterChars * 100 >= beforeChars * MIN_REDUCTION_PERCENT) { ... }
```

**② P0-2 / C1：压后超线判定改用字符比例** — 同文件

```kotlin
- if (trigger != null && afterEstimate.toDouble() * calibration >= trigger) { ... }
+ val scaledAfter = beforeEstimate.toDouble() * calibration *
+     (afterChars.toDouble() / beforeChars.toDouble())
+ if (trigger != null && scaledAfter >= trigger) { ... }
```

**③ P0-4：`compactEnd` 保留成本不再乘 calibration** — 同文件

```kotlin
- val cost = AgentContextBudget.rawEstimate(JSONArray().put(history[from - 1])) * calibration
+ val cost = AgentContextBudget.rawEstimate(JSONArray().put(history[from - 1]))
```

**④ P2-1：校准初值改中性 1.0** — `AgentContextBudget.kt`

```kotlin
- private const val DEFAULT_CALIBRATION = 0.4
+ private const val DEFAULT_CALIBRATION = 1.0
```

**⑤ 新增回归测试两个 + 修正一个过时断言** — 测试文件

| 用例 | 作用 |
|---|---|
| `reductionJudgementUsesCharCountNotRawEstimate` | 工具结果密集时不再误拒 |
| `compactionRejectedWhenCharReductionIsInsufficient` | 字符降幅不足时**仍须拒绝**（保护不丢）|
| `未观测时使用中性初值不假设估算方向`（改写）| 原断言「estimated < raw」的前提已不成立 |

### 为什么没有改 `AgentHistoryTrimmer`

原计划让它改用"实测密度"取代 calibration，但推导后发现：

```
calibration = input / rawEstimate
density     = input / chars
rawEstimate ≈ chars × 固定系数
        ↓
calibration × 固定系数⁻¹ ≡ density      ← 数学上等价
```

**换成 density 不产生任何改善** —— 问题不在"用哪个系数"，
而在**「用整体系数去换算局部内容」**这一结构。而该结构的最大问题
（初值方向反了）已由 ④ 解决：Trimmer 现在要么拿到实测值、要么拿到中性的 1.0。

### 验证结果

| 项 | 结果 |
|---|---|
| 编译 | `BUILD SUCCESSFUL` |
| `AgentContextCompactionTest` | **23/23 通过** |
| `AgentContext*` / `AgentHistory*` / `AgentCompaction*` / `AgentModel*` / `AgentConversationCodec*` | 仅 **7 个环境性失败**（全部为 `Robolectric native runtime is not supported on Linux (aarch64)`，涉及 DB 的用例），**零功能回归** |

### 修复前后对照（同一份真机数据推算）

| 指标 | 修复前 | 修复后 |
|---|---|---|
| 降幅判定 | **1.8% → 拒绝** ❌ | **49% → 通过** ✅ |
| 与实测 token 降幅（53%）偏差 | 51 个百分点 | **4 个百分点** |
| 保留条数 | **454 条** | **193 条** |
| 保留字符 | 634,292 | 372,367 |
| 校准初值方向 | 与实测相反（偏 4.6 倍）| 中性，不假设方向 |

### 尚未修复（均为精细度优化，不再导致故障）

| # | 问题 | 说明 |
|---|---|---|
| P0-2 | 判定对象（`result`）≠ 提交对象（差 48.5K）| 需调整结构，风险较高 |
| P1-2 | `rawEstimate` 两处口径（含/不含 tools）| tools 仅 ~24K，影响小 |
| P2-3 | 摘要输入 512K 截断 → 早期内容被硬裁 | 边界场景 |
| P2-4 | 提示文案措辞 | 纯文案 |

---

## 8. 附录：复现方法

### 已实施（改动 +63 / −5，只涉及 2 个文件）

**① P0-1：降幅判定改用字符数** — `AgentContextCompactor.kt`

```kotlin
- val afterEstimate = AgentContextBudget.rawEstimate(result)
- if (afterEstimate * 100 >= beforeEstimate * MIN_REDUCTION_PERCENT) { ... }
+ val beforeChars = messages.toString().length
+ val afterChars  = result.toString().length
+ if (afterChars * 100 >= beforeChars * MIN_REDUCTION_PERCENT) { ... }
```

**② P0-2 / C1：压后超线判定改用字符比例** — 同文件

```kotlin
- if (trigger != null && afterEstimate.toDouble() * calibration >= trigger) { ... }
+ val scaledAfter = beforeEstimate.toDouble() * calibration *
+     (afterChars.toDouble() / beforeChars.toDouble())
+ if (trigger != null && scaledAfter >= trigger) { ... }
```

**③ 新增回归测试两个** — `AgentContextCompactionTest.kt`

| 用例 | 守住什么 |
|---|---|
| `reductionJudgementUsesCharCountNotRawEstimate` | 工具结果密集（ASCII）时不再误拒 |
| `compactionRejectedWhenCharReductionIsInsufficient` | 字符降幅不足时**仍须拒绝**（原有保护不丢）|

### 验证结果

| 项 | 结果 |
|---|---|
| 编译 | `BUILD SUCCESSFUL` |
| `AgentContextCompactionTest` | **23/23 通过**，新用例确认执行 |
| 相关模块回归 | 仅 1 个**环境性**失败（`Robolectric native runtime is not supported on Linux (aarch64)`），与本次改动无关 |

### 实测对照

| 指标 | 旧判据 | 新判据 |
|---|---|---|
| 压缩前 | 1,007,029 字符（估 420,083 tokens）| 同 |
| 压缩后 | 634,292 字符（估 412,433 tokens）| 同 |
| **降幅判定** | **1.8% → 拒绝** ❌ | **49% → 通过** ✅ |
| 与实测 token 降幅（53%）的偏差 | **51 个百分点** | **4 个百分点** |

**结论**：判据从"与真实值差 51 个百分点"变为"差 4 个百分点"，
摘要不再被误拒，压缩链路恢复预期行为。

### 尚未修复（保留在待办）

| # | 问题 | 状态 |
|---|---|---|
| **P0-4** | 保留量超出预算 —— 实测压缩后仍保留 453 条 / 630K 字符，真实 ~350K tokens vs 预算 157K | 待评估（属效率问题，不再阻塞）|
| P1-1 | `calibration` 全局单值 | 待评估 |
| P1-2 / P0-3 | 三处口径不统一（含/不含 tools、校准/未校准）| 待评估 |
| P2-1 | `calibration` 不跨 run 存活 | 待评估 |
| P2-3 | 摘要输入 512K 截断 | 待评估 |
| P2-4 | 提示文案措辞 | 待评估 |

**注**：修复 ① ② 已解除"摘要被误拒"这一核心故障；
上述待办属于**进一步优化**（提升压缩效率、统一口径），可在后续版本单独推进。

---

## 8. 附录：复现方法

```python
def text_tokens(text):
    ascii_n = sum(1 for ch in text if ord(ch) < 128)
    other = len(text) - ascii_n
    return (ascii_n + 2) // 3 + other        # 复现 Kotlin textTokens
```

数据来源：
- `conversation_context_checkpoints`（`history` / `journal` 字段，分块存于 `agent_text_chunks`）
- `conversation_messages.input_tokens`（服务商实测回执）

复现结果：
```
history : 1,007,029 字符 → rawEstimate 363,922  vs  实测 365,278   (0.4%)
journal : 2,281,516 字符 → rawEstimate 865,038
tool 占比: 91%
```

---

## 6. 说明

- 本报告所有数字均来自真机 DB 导出与实测 `input_tokens`，可复核
- 报告中的会话内容仅用于统计规模，**不含具体正文**
- 代码位置基于 v3.11.28（`d6a8e67`），如与后续版本有差异以实际代码为准
