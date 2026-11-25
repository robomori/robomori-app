#!/bin/bash
# Downloads hev-socks5-tunnel 2.20.0 and the libraries it builds with, at the commits that release pins, into
# build/hev-socks5-tunnel, and checks each download against its known hash so every build compiles the same code.
set -euo pipefail
cd "$(dirname "$0")"
DEST=build/hev-socks5-tunnel
SOURCES=(
    ".:hev-socks5-tunnel:75dbc1e9ace6c70d3d1b94df7c283efe1de7effc"
    "src/core:hev-socks5-core:b43fa857fb39c854da01d13bc55eded66b4f4a3b"
    "third-part/hev-task-system:hev-task-system:ded55c412b3951bc7decb5cb3b9135701e762998"
    "third-part/lwip:lwip:8115cd6150ab32500db3d4d8bd8ad7ec6648534c"
    "third-part/yaml:yaml:0c82efd10dc67b9e7f159d2c7f5d1dc3c1b5bc11"
)
rm -rf "$DEST" build/downloads
mkdir -p "$DEST" build/downloads
for source in "${SOURCES[@]}"; do
    IFS=: read -r path repository commit <<< "$source"
    archive="build/downloads/$repository.tar.gz"
    curl -fsSL "https://codeload.github.com/heiher/$repository/tar.gz/$commit" -o "$archive"
    expected="$(grep " $repository\$" sources.sha256 | cut -d' ' -f1 || true)"
    actual="$(shasum -a 256 "$archive" | cut -d' ' -f1)"
    if [ "$expected" != "$actual" ]; then echo "$repository: expected sha256 $expected, got $actual" >&2; exit 1; fi
    mkdir -p "$DEST/$path"
    tar -xzf "$archive" -C "$DEST/$path" --strip-components 1
done
