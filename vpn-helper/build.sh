#!/bin/bash
# Builds Robomori's VPN helper app into the app's resources. Run after changing anything in this folder, and commit the
# APK: the Linux and Windows release builds have no Android SDK. Robomori installs it on a device when the copy there
# differs, so it is signed with the same key on every build, for the update to install over it. The key and its password
# live in the login keychain, as robomori-vpn-helper-keystore (base64) and robomori-vpn-helper-keystore-password.
set -euo pipefail
cd "$(dirname "$0")"
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
NDK="$(ls -d "$SDK"/ndk/* | sort -V | tail -1)"
TOOLS="$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)"
PLATFORM="$SDK/platforms/android-36/android.jar"
OUT=../src/main/resources/vpn-helper
VERSION=4

./fetch.sh
patch -s -p1 -d build/hev-socks5-tunnel < session-origin.patch

rm -rf build/ndk build/classes build/apk && mkdir -p build/classes build/apk/lib
"$NDK/ndk-build" -s -j8 NDK_PROJECT_PATH=. APP_BUILD_SCRIPT=jni/Android.mk NDK_APPLICATION_MK=jni/Application.mk \
    NDK_OUT=build/ndk/obj NDK_LIBS_OUT=build/apk/lib

"$TOOLS/aapt2" compile --dir res -o build/res.zip
"$TOOLS/aapt2" link -o build/apk.zip -I "$PLATFORM" --manifest AndroidManifest.xml build/res.zip --java build/gen \
    --version-code "$VERSION" --version-name "$VERSION"
javac -source 8 -target 8 -Xlint:-options -bootclasspath "$PLATFORM:$TOOLS/core-lambda-stubs.jar" -d build/classes \
    $(find src build/gen -name '*.java')
"$TOOLS/d8" --release --min-api 24 --lib "$PLATFORM" --output build/apk $(find build/classes -name '*.class')

# The same bytes for the same sources, so a rebuild with no change doesn't make devices install the helper again.
(cd build/apk && find . -exec touch -t 202601010000 {} + && zip -q -X -r ../apk.zip classes.dex lib)
"$TOOLS/zipalign" -f -P 16 4 build/apk.zip build/aligned.apk
mkdir -p "$OUT"
security find-generic-password -s robomori-vpn-helper-keystore -w | base64 -d > build/helper.p12
trap 'rm -f build/helper.p12' EXIT
ROBOMORI_HELPER_KEYSTORE_PASSWORD="$(security find-generic-password -s robomori-vpn-helper-keystore-password -w)" \
    "$TOOLS/apksigner" sign --ks build/helper.p12 --ks-pass env:ROBOMORI_HELPER_KEYSTORE_PASSWORD \
    --out "$OUT/robomori-helper.apk" build/aligned.apk
ls -l "$OUT/robomori-helper.apk"
