# 修复方案执行清单（全局代码审查与正收益修复方案）

来源：`XunEr-agent-全局代码审查与正收益修复方案.md`（M0→M4 里程碑）+ 2026-10-04 取证后的方案修订
（locate 先行、OCR 上调为必需、M2.2 减半、M3/M4 选择性执行）。
**做完就打勾，避免重复开工。** 状态含义：✅ 完成 · 🟡 部分 · ⏸ 按序等待 · ❌ 未做/搁置。

## M0 固化已有正收益
- [x] 不变量清单入 docs（F1–F7 源码/测试一对一映射 + 2 条如实缺口）→ `docs/INVARIANTS.md`
- [x] 测试门禁落档（本地=非环境失败相对基线 0；环境类别清单；CI 全量）
- [x] Changelog 三分类规范（prompt 约束禁止写成根因修复）→ INVARIANTS.md 尾节
- [x] 补 F7 测试锁（AgentImageToolsTest：image_width/height + 坐标警告）

## M1 去掉负收益手段 + 失败可恢复性
- [x] M1.2 压缩失败可恢复：compact 失败降级硬裁（不再整 run 毙命）→ 裁后仍超窗抛可恢复 `CONTEXT_EXHAUSTED`（带下一步指引）；新事件 `HistoryTrimmed`（wire 序列化+解析+UI 系统通知）；档位关系锁 TRIGGER 0.75 < TARGET 0.95 单测通过
- [x] M1.3 memory_get 重复页拦截：同参同 revision 分页重读返回 `DUPLICATE_PAGE` + 强制 next_start_line；只比对最后一页（读其他页解锁）+ revision 进 key（写入解锁）
- [ ] M1.4 敏感策略表单一真源 —— **⏸ 等用户确认产品立场**（会推翻"功能优先于隐私"取舍）
- [ ] M1.1 删 6%/PIL 文案 —— **⏸ 按方案时序等 OCR 落地**（现为 locate 优先、像素分析后备）

## M2 设备侧定位与点击契约（核心）
- [x] M2.1 locate_on_screen 树匹配通道（ScreenLocator 纯匹配 + 快照自刷新 + observe 同套记账 + LOCATE_MISS/LOCATE_UNAVAILABLE）
- [x] M2.1 引导接入（稀疏树 locate 优先）+ 图标/requirements/trace 注册
- [ ] M2.1 OCR 通道（ML Kit，+15-20MB）—— **⏸ 等用户拍板体积**，v3.5.1 目标
- [ ] M2.1 region 参数 —— ❌ 暂无需求，不做
- [ ] M2.2 坐标锚定收紧 —— ❌ 未做；**只做减半版**（拦无观察裸坐标），等 locate 真机反馈定
- [x] M2.3 编码尺寸 ≡ coordinate_contract.screenshot 回归锁：契约构建抽为纯函数 buildCoordinateContract + CoordinateContractTest 3 例（本地绿）；三层等式=codec 不缩放锁→构造点→契约锁

## M3 工具层结构化与可维护性（选择性）
- [ ] M3.1 拆 AgentLocalTools —— ❌ 搁置（用户同意；有痛点再动）
- [ ] M3.2 浏览器等待可取消（超时预算 + 可取消 future）—— ❌ 低优先级
- [ ] M3.3 结构化诊断导出（run 序列/错误码/trim·compact 标记，不含敏感原文）—— 待做

## M4 Hook/无障碍/语音
- [ ] M4 拆大文件/ROM 整理 —— ❌ 搁置（用户同意）

## 发版记录
- [x] v3.4.9 引导层（prompt 约束；2026-10-04 取证：升级触发、首中率未改善）
- [ ] v3.5.0（M0 + M2.1 树匹配）—— CI 第 2 轮运行中，出包后用户装机验证
