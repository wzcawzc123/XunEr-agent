#!/usr/bin/env python3
"""把 Gradle 单元测试结果汇总成 GitHub Step Summary。

Gradle 默认只在失败时打印，全绿的时候日志里看不到"到底跑了多少例"，
只能看到一行 BUILD SUCCESSFUL。这个脚本读 test-results 的 XML，
把总数/通过/失败/跳过写进 Job Summary，让"全绿"留下可核对的数量。

即使解析出错也退出 0：汇总失败不该把构建判红。
"""

from __future__ import annotations

import glob
import os
import sys
import xml.etree.ElementTree as ET

VIEW_LIMIT = 15


def parse_results(patterns: list[str]) -> tuple[dict, list[tuple[str, str, str]], list[str]]:
    totals = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0, "time": 0.0}
    failed: list[tuple[str, str, str]] = []

    files: list[str] = []
    for pattern in patterns:
        # 允许直接传目录（CI 里就是这么传的），自动展开成 <dir>/*.xml
        if os.path.isdir(pattern):
            pattern = os.path.join(pattern, "*.xml")
        files.extend(glob.glob(pattern))
    files = sorted(set(files))

    for path in files:
        try:
            root = ET.parse(path).getroot()
        except (ET.ParseError, OSError) as exc:  # 单个文件坏了不拖垮汇总
            print(f"warn: 跳过无法解析的 {path}: {exc}", file=sys.stderr)
            continue

        suites = [root] if root.tag == "testsuite" else root.findall("testsuite")
        for suite in suites:
            for key in ("tests", "failures", "errors", "skipped"):
                totals[key] += int(float(suite.get(key) or 0))
            totals["time"] += float(suite.get("time") or 0)

            for case in suite.findall("testcase"):
                node = case.find("failure")
                if node is None:
                    node = case.find("error")
                if node is None:
                    continue
                message = (node.get("message") or node.get("type") or "").strip()
                first_line = message.splitlines()[0] if message else ""
                failed.append(
                    (
                        case.get("classname") or "?",
                        case.get("name") or "?",
                        first_line[:180],
                    )
                )

    return totals, failed, files


def render(totals: dict, failed: list[tuple[str, str, str]], files: list[str]) -> str:
    lines: list[str] = []

    if not files:
        lines.append("## 单元测试结果")
        lines.append("")
        lines.append("> 没有找到 test-results，测试可能没跑起来（检查上一步）。")
        return "\n".join(lines)

    tests = totals["tests"]
    executed_failed = totals["failures"] + totals["errors"]
    passed = max(tests - executed_failed - totals["skipped"], 0)

    lines.append("## 单元测试结果")
    lines.append("")
    lines.append("| 用例 | 通过 | 失败 | 跳过 | 耗时 |")
    lines.append("| ---: | ---: | ---: | ---: | ---: |")
    lines.append(
        f"| {tests} | {passed} | {executed_failed} | {totals['skipped']} | {totals['time']:.1f}s |"
    )
    lines.append("")
    lines.append(f"<sub>{len(files)} 个测试类结果文件</sub>")

    if failed:
        lines.append("")
        lines.append(f"<details><summary>失败用例（{len(failed)}，最多列 {VIEW_LIMIT}）</summary>")
        lines.append("")
        for classname, name, message in failed[:VIEW_LIMIT]:
            lines.append(f"- `{classname}.{name}` — {message}")
        if len(failed) > VIEW_LIMIT:
            lines.append(f"- ……其余 {len(failed) - VIEW_LIMIT} 条见上传的 test-results")
        lines.append("")
        lines.append("</details>")

    return "\n".join(lines)


def main() -> int:
    patterns = sys.argv[1:] or ["app/build/test-results/testDebugUnitTest/TEST-*.xml"]

    try:
        totals, failed, files = parse_results(patterns)
        report = render(totals, failed, files)
    except Exception as exc:  # noqa: BLE001 - 汇总不该让构建变红
        report = f"## 单元测试结果\n\n> 汇总失败：`{exc}`"
        print(f"warn: 汇总失败: {exc}", file=sys.stderr)

    print(report)

    summary_path = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary_path:
        with open(summary_path, "a", encoding="utf-8") as handle:
            handle.write(report + "\n")

    return 0


if __name__ == "__main__":
    sys.exit(main())
