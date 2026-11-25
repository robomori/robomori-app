#!/bin/bash
# Builds the network agent for each Android ABI into the app's resources. Run after changing agent.c, and commit the
# libraries: the Linux and Windows release builds have no NDK. include/jvmti.h is the JDK's, which ART follows.
set -euo pipefail
cd "$(dirname "$0")"
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
NDK="$(ls -d "$SDK"/ndk/* | sort -V | tail -1)"
BIN="$(echo "$NDK"/toolchains/llvm/prebuilt/*/bin)"
OUT=../src/main/resources/network-agent
# Android 9 (API 28) is the first that loads agents into apps.
for pair in arm64-v8a:aarch64-linux-android28 armeabi-v7a:armv7a-linux-androideabi28 x86_64:x86_64-linux-android28 x86:i686-linux-android28; do
    abi="${pair%%:*}"; target="${pair#*:}"
    mkdir -p "$OUT/$abi"
    "$BIN/$target-clang" -shared -fPIC -Os -fvisibility=hidden -Wall -Werror -Iinclude agent.c -llog \
        -Wl,--build-id=none -s -o "$OUT/$abi/librobomori-network.so"
done
ls -l "$OUT"/*/librobomori-network.so
