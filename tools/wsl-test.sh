#!/bin/bash
# WSL 本地测试：把 Windows checkout 镜像到 Linux 文件系统后跑单测。
#
# 动机（2026-10-22）：Windows 上跑 Gradle 单测有 58 个平台噪音红，没法当验收门，
# 此前只能每轮推 CI 验证。WSL（Linux）与 CI 同口径——本地跑绿 = CI 会绿，
# 于是 CI 只需在里程碑推送时跑一次。
#
# 两个 WSL 特有的坑（都已在脚本内处理）：
# 1. Windows checkout 的 shell 脚本带 CRLF → 同步后统一转 LF；
# 2. WSL 默认 root：权限类断言（setWritable/canWrite）在 root 下恒真，与 CI 不同口径
#    → 自动切到非 root 用户 builder 跑 Gradle。
#
# 用法（Windows PowerShell 里）：
#   wsl -e bash /mnt/c/Users/11653/WorkBuddy/薰儿/XunEr-agent/tools/wsl-test.sh
#   wsl -e bash tools/wsl-test.sh --tests "*AgentCostSummary*"   # 靶向
# 退出码：0 = 全绿；1 = 有失败（与 CI 语义一致）。
set -euo pipefail

SRC="/mnt/c/Users/11653/WorkBuddy/薰儿/XunEr-agent"
DST="/srv/xuner-agent"  # 固定绝对路径：两阶段 HOME 不同（root ↔ builder），不能用 $HOME 拼
# SDK 优先用 WSL 原生 Linux SDK：Windows SDK（/mnt/d）的 aapt 是 .exe，Linux Gradle 不认。
# 原生 SDK 由 tools/wsl-setup-sdk.sh 一次性安装（platforms;android-37.0 + build-tools 36/37）。
if [ -x "/opt/android-sdk/build-tools/37.0.0/aapt2" ]; then
    export ANDROID_HOME="/opt/android-sdk"
else
    export ANDROID_HOME="/mnt/d/Android/Sdk"
fi
export JAVA_HOME="/usr/lib/jvm/java-25-openjdk-amd64"
export PATH="$JAVA_HOME/bin:$PATH"

# ---- 阶段一：root 同步（需要写 DST 与 chown） ----
if [ "${WSL_TEST_PHASE:-sync}" = "sync" ]; then
    echo "== 同步源码 → $DST（排除构建产物/.git）=="
    mkdir -p "$DST"
    rsync -a --delete \
      --exclude '.gradle' \
      --exclude '.idea' \
      --exclude 'build' \
      --exclude 'app/build' \
      --exclude '.git' \
      --exclude 'app/build/probe' \
      "$SRC/" "$DST/"

    # Windows checkout 的 shell 脚本带 CRLF，Linux 直接跑会报 `/bin/sh^M: bad interpreter`。
    find "$DST" -maxdepth 2 -name '*.sh' -exec sed -i 's/\r$//' {} +
    sed -i 's/\r$//' "$DST/gradlew"
    chmod +x "$DST/gradlew"

    # WSL 侧 SDK 路径（Windows 的 local.properties 指向 D:\Android\Sdk，不能直接用）
    echo "sdk.dir=$ANDROID_HOME" > "$DST/local.properties"

    # 权限类断言同口径：root 下 setWritable(false)/canWrite 语义与 CI（非 root）不同，
    # 会冤枉 TerminalPrivateStorageTest / DexKitNativeLibraryFileTest 三个用例。
    if [ "$(id -u)" = "0" ]; then
        id builder >/dev/null 2>&1 || useradd -m builder
        chmod -R a+rX "$ANDROID_HOME" 2>/dev/null || true
        chown -R builder:builder "$DST" 2>/dev/null || true
        mkdir -p /opt/gradle-home
        chown -R builder:builder /opt/gradle-home
        exec runuser -u builder -- env \
            WSL_TEST_PHASE=run \
            HOME=/home/builder \
            GRADLE_USER_HOME=/opt/gradle-home \
            ANDROID_HOME="$ANDROID_HOME" \
            JAVA_HOME="$JAVA_HOME" \
            PATH="$JAVA_HOME/bin:$PATH" \
            bash "$0" "$@"
    fi
fi

# ---- 阶段二：跑测试（builder 用户） ----
cd "$DST"
echo "== ./gradlew testDebugUnitTest $*（$(whoami)）=="
code=0
./gradlew testDebugUnitTest --console=plain "$@" || code=$?

# 汇总与 CI 同口径的数字：tests / failures / errors / skipped
python3 - <<'PY'
import glob, re
t = f = s = 0
failed_classes = []
for p in glob.glob('app/build/test-results/testDebugUnitTest/*.xml'):
    d = open(p, encoding='utf-8').read()
    m = re.search(r'tests="(\d+)" skipped="(\d+)" failures="(\d+)" errors="(\d+)"', d)
    if m:
        t += int(m.group(1)); s += int(m.group(2))
        n = int(m.group(3)) + int(m.group(4))
        f += n
        if n:
            failed_classes.append(p.split('TEST-')[-1].replace('.xml', ''))
print(f'== 单测汇总：tests={t} failed={f} skipped={s} ==')
if failed_classes:
    print('失败类：')
    for c in sorted(failed_classes):
        print(' -', c)
PY

if [ "$code" -ne 0 ]; then
    echo "== 退出码 $code（有失败）=="
    exit 1
fi
echo "== 全绿 =="
