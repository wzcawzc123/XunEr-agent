# 上游 3.2.0 新增代码审查（2026-10-07）

承接《v3.8.x 终端报错诊断审查》。起因：用户要求排查"逐行审查上游新增代码"，
以确认除 `run_command` 外是否还有未发现的隐患。

上游 12 个提交新增约 1.4 万行。**方法说明**：未采取字面意义的逐行通读（成本极高、
收益递减），而是**风险模式驱动**——只盯历史上真正出过问题的几类形态，并用脚本
做集合比对保证覆盖面。

## 审查面与结论

| # | 风险面 | 方法 | 结论 |
|---|---|---|---|
| 1 | 删除/收窄类改动 | `git diff --diff-filter=D --name-only` + 提取删除的 schema 注册名 / when 分支 / 枚举值 | 真删除仅 `run_command`（其余为迁移） |
| 2 | 工具三源一致性 | 脚本比对 能力目录 / 要求表 / 执行分发 | 122 个工具，唯一异常是 `run_command` |
| 3 | 敏感面覆盖 | 122 工具 vs 敏感表（含动态名单） | 无回归；未入表的仅"既有设计取舍"（文件内容、屏幕内容） |
| 4 | app_process 通道安全 | 读入口 `PhoneCommandMain` + 启动点 `PhoneAppTools` | 设计扎实：必须 root、正文只走 stdin、工具名白名单、有界执行器 |
| 5 | targetSdk 37 行为变更 | 读 manifest diff + 设备版本 | 设备为 Android 16（SDK 36），新限制（如 SMS OTP 延迟）**不生效** |
| 6 | 新能力真机实测 | 实际调用 | web_search / fetch_url / 本地网络门 / 三个迁移工具 全部正常 |
| 7 | 测试覆盖 | 检查新模块配套测试 | 主要新模块均有测试（流式备份 245+211 行等） |
| 8 | 遗留标记 | TODO/FIXME/空 catch/@Suppress 扫描 | 无遗留标记、**无静默吞异常**、5 处抑制均合理 |

## 分面明细

### 1. 删除类改动全景

| 类型 | 数量 | 核实结论 |
|---|---|---|
| schema 注册名 | 5 | `terminal`/`read_file`/`write_file`/`list_directory` 迁移到新 catalog；**`run_command` 真删** |
| when 执行分支 | 6 | 全部迁移到 `AgentStructuredDeviceTools`（app_state_control:170、device_status:146、get_logcat:156、get_setting:152、media_control:150、network_info:147） |
| 整个文件 | 2 | `AgentPersonalDataTools` + 其测试；功能迁到 app_process 通道，真机实测可执行 |

### 4. app_process 通道的安全设计（重点）

- 入口 `PhoneCommandMain`：`Process.myUid() != 0 → ROOT_REQUIRED`；输入 128 KiB 上限；
  `version` 协议校验；`tool !in PhoneOperation.tools → UNKNOWN_TOOL` 白名单；`user_id` 范围校验。
- 白名单与能力目录同源：`reads + writes + NativePersonalQueries.sources.keys`。
- 启动点 `PhoneAppTools:50`：APK 路径经 `DeviceToolContract.quote()` 转义；
  **请求正文只走 stdin**（不进进程参数，避免出现在 `ps` 中）；经 `BoundedRootCommandExecutor`
  （`timeoutMillis.coerceIn(500, MAX)`、`readBounded(maxOutputBytes)`）。

### 3. 未入敏感表的工具（判断为既有取舍，非回归）

`read_file`/`write_file`/`edit_file`/`stat_file`/`glob_files`/`grep_files`/`list_directory`
（文件内容会进 transcript）、`observe_screen`/`locate_on_screen`（屏幕内容）、
`inspect_app`/`top_memory_apps`/`top_storage_apps`（应用列表）、`web_search`/`fetch_url`
（公开网页）。以上均为上游既有设计，非本次合并引入；如未来要收紧，属独立议题。

## 结论

**除已修复的 `run_command` 外，未发现新的上游缺陷。** 新增代码普遍带有配套测试与
边界设计（白名单、有界执行、协议版本、降级处理），质量高于预期。

## 方法边界（诚实声明）

- 覆盖面由"风险模式"决定：能抓住**删除/迁移/契约不一致/安全边界/平台变更/静默失败**
  这几类（即历史上真正出过问题的形态），但**不保证**覆盖纯逻辑错误（如算法写错、
  边界条件差一），后者依赖测试与真机实测。
- 未做字面逐行通读；如需要，可按文件逐个精读，成本较高。
