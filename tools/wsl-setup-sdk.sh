#!/bin/bash
# 在 WSL 里安装原生 Linux Android SDK（一次性）。
#
# 为什么需要：Windows SDK 的 aapt 是 .exe，Linux 的 Gradle 不认
# （报 "Installed Build Tools revision 36.0.0 is corrupted"）。
# 装好后 tools/wsl-test.sh 会优先使用它，本地单测与 CI 同口径。
set -euo pipefail

SDK="/opt/android-sdk"
mkdir -p "$SDK/cmdline-tools"
cd "$HOME"

if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
    curl -sO https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
    rm -rf "$SDK/cmdline-tools/cmdline-tools"
    unzip -qo commandlinetools-linux-11076708_latest.zip -d "$SDK/cmdline-tools"
    mv "$SDK/cmdline-tools/cmdline-tools" "$SDK/cmdline-tools/latest"
fi

yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" --licenses > /dev/null 2>&1 || true
# 版本与 app/build.gradle.kts 对齐：compileSdk 37（平台目录名 android-37.0）、build-tools 36/37
"$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" \
    "platforms;android-37.0" "build-tools;36.0.0" "build-tools;37.0.0" "platform-tools"

echo "== WSL SDK 就绪：$SDK =="
ls "$SDK/platforms" "$SDK/build-tools"
