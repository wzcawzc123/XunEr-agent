# fork 接线全面审查（2026-10-28）

> 触发：模型视觉开关「点了没反应」。追根因发现是 10-21「重装开关」时把行 id 写成了 API 模型名，
> 属「UI ↔ 状态层标识契约错位」类 bug。本文按四个维度把 fork 内容全面过一遍，避免同类意外。

## 方法

1. **静默失败扫描**：全仓 `catch (_: Throwable/Exception) {}` 与用户动作路径上的 `return@launch`
2. **ID 契约审计**：UI 传给状态层的每个标识，与状态层匹配的字段是否同一命名空间
3. **Action 死角**：声明/发出但处理为空（`-> Unit`）的动作
4. **旧 fork 功能对齐**：文件级 + 符号级对照旧克隆（D:\deepseek\XunEr-agent，e430638e）

## 结论

### ✅ 已修复（本次）

| 问题 | 根因 | 修复 |
|---|---|---|
| 视觉开关无效 | `onVisionToggled` 传 `model.modelId`（API 名），`setModelVision` 按 `it.id`（行 id）匹配；外层 catch 吞异常 | 调用点恢复传 `model.id`；`ModelRepository.findForUi` 双标识兜底；字段改名 `apiModelId`；契约测试锁定 |

时间线：09-07 `3257285c` 首次加开关（model.id，正常）→ 10-14 `d4789a83` 重做选择器 →
10-21 `117142d8` 装回开关时误传 model.modelId（坏）→ 10-28 修复。

### ✅ 检查通过

- **静默 catch**：全仓仅 1 处（视觉开关所在函数），无第二处系统性吞异常
- **ID 契约**：全应用约定 `modelId` = 行 id（`selectModel` / `Provider.enabledModel` / `RuntimeConfigRepository`），
  视觉开关是唯一异类；会话/消息/角色/技能/MCP/终端任务的 id 传递均一致
- **开关刷新**：技能开关（`refreshSkills()`）、提供商模型开关（本地表单状态）、记忆模式、
  角色世界书均正确回读或本身就是本地状态
- **旧 fork 功能**：语言切换、MCP 折叠、媒体/相机/超大图降级、terminal 全家桶、hyperos 适配、
  rootless 隔离、记忆 4.0、用量统计、续跑、压缩、androguard 均在位

### ⚠️ 已知遗留（非本次回归）

1. **`ExpandRunTrace` 是空动作**：`AgentHomeScreen` 发出、`AgentAppRoot` 处理为 `Unit`。
   旧 fork 同样如此（祖传），不影响主流程；如需要轨迹面板属新功能，不是修复。
2. **`UserFileAccess` 被上游重构取代**：旧 fork 用显式白名单限制非 root 文件访问；
   现用 `identity=user`（App uid）+ OS 沙箱 + 工具描述约束。安全边界仍在（OS 层），
   但少了显式路径校验这一层纵深，属产品取舍，记录在案。
3. **`AgentPersonalDataTools` 被上游 `PersonalSearchTools` 取代**：功能未丢。

### 🛡 防复发机制（已落地）

1. **命名即契约**：`AgentModelOptionUi.modelId → apiModelId`（行 id 仍叫 `id`），注释写明教训
2. **契约测试**：`project_keepsRowIdAndApiModelIdDistinct` 锁定「id=行 id、apiModelId=API 名」
3. **容忍兜底**：`ModelRepository.findForUi`（行 id 优先 / API 名回退），4 例回归测试
4. **模板**：本次方法（静默 catch / ID 契约 / Action 死角 / 旧 fork 对齐）可复用于下次合并后自查

## 验证

- 单测 2082 例全绿（新增 5 例：4 双标识 + 1 契约）
- 真机验证：待设备回连后拨动开关确认（标签切换 + 重启保持）
