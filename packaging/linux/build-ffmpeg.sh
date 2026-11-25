#!/usr/bin/env bash
# Builds the small libavcodec and libavutil that Linux mirroring decodes the phone's screen with: FFmpeg with only its
# H.264 decoder, as shared libraries that need nothing beyond the C library. scrcpy's Windows release ships the same
# libraries as DLLs; its Linux release links them into scrcpy itself, so Robomori builds its own.
#
#   build-ffmpeg.sh OUT ARCH      ARCH is x86_64 or aarch64; an x86_64 build cross-compiles on an aarch64 machine
#
# Needs a C compiler for ARCH (gcc, or x86_64-linux-gnu-gcc when cross-compiling), make, nasm for x86_64, curl and xz.
set -euo pipefail
out="$(mkdir -p "$1" && cd "$1" && pwd)"
arch="$2"
version=9.0.2
checksum=8c3850283eb25fa026482078a04051e0be17347b09ef81a0849bec15a96e002e
work="${TMPDIR:-/tmp}/robomori-ffmpeg-$version-$arch"
mkdir -p "$work"
cd "$work"
if [[ ! -d "ffmpeg-$version" ]]; then
  curl -fsSL --retry 5 -o "ffmpeg-$version.tar.xz" "https://ffmpeg.org/releases/ffmpeg-$version.tar.xz"
  echo "$checksum  ffmpeg-$version.tar.xz" | sha256sum -c -
  tar xf "ffmpeg-$version.tar.xz"
fi
cd "ffmpeg-$version"
conf=(
  --prefix="$work/install" --enable-shared --disable-static
  --disable-programs --disable-doc --disable-everything --disable-network --disable-autodetect
  --disable-avformat --disable-avdevice --disable-avfilter --disable-swscale --disable-swresample
  --enable-decoder=h264 --enable-parser=h264
  # LGPL only: nothing GPL or non-free is enabled.
  --extra-cflags="-O2 -fPIC"
)
if [[ "$(uname -m)" != "$arch" ]]; then
  conf+=(--enable-cross-compile --cross-prefix="$arch-linux-gnu-" --arch="$arch" --target-os=linux)
fi
make distclean >/dev/null 2>&1 || true
./configure "${conf[@]}" > "$work/configure.log"
make -j"$(nproc)" > "$work/make.log"
make install > /dev/null
# The versioned names the libraries ask for each other by, such as libavcodec.so.62 and libavutil.so.60.
for library in avutil avcodec; do
  file="$(readlink -f "$work/install/lib/lib$library.so")"
  soname="$(basename "$(ls "$work/install/lib/lib$library.so."[0-9]* | awk 'length < min || !min { min = length; name = $0 } END { print name }')")"
  cp "$file" "$out/$soname"
done
cp COPYING.LGPLv2.1 "$out/LICENSE-ffmpeg"
ls -la "$out"
