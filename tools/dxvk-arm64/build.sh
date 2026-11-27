#!/usr/bin/env bash
# Cross-compile DXVK 1.10.x (Vulkan 1.1 is enough) for Windows ARM64 and package it as a Winlator content (.wcp).
#
# Needs in PATH: llvm-mingw (aarch64-w64-mingw32-clang, llvm-dlltool, llvm-nm), meson, ninja, glslangValidator,
# git, python3, tar, zstd.
#
# Usage: build.sh <dxvk-git-ref> <work-dir> <out-dir>
# Output: <out-dir>/d3d11.dll, <out-dir>/dxgi.dll and <out-dir>/dxvk-<ver>-arm64-mali.wcp
set -euo pipefail

REF="${1:?dxvk ref}"
WORK="$(mkdir -p "${2:?work dir}" && cd "$2" && pwd)"
OUT="$(mkdir -p "${3:?out dir}" && cd "$3" && pwd)"
HERE="$(cd "$(dirname "$0")" && pwd)"
VER="${REF#v}"
PKG_VER="$VER-arm64-mali"

cd "$WORK"
rm -rf dxvk
git clone --branch "$REF" --depth 1 --recurse-submodules --shallow-submodules https://github.com/doitsujin/dxvk.git dxvk
git -C dxvk log -1 --format='DXVK %H %s'

python3 "$HERE/patch_dxvk.py" dxvk

cat > cross-arm64.txt <<'EOF'
[binaries]
c = 'aarch64-w64-mingw32-clang'
cpp = 'aarch64-w64-mingw32-clang++'
ar = 'llvm-ar'
strip = 'llvm-strip'
windres = 'aarch64-w64-mingw32-windres'

[built-in options]
# Newer libc++ no longer includes these transitively; DXVK 1.10 relies on that.
cpp_args = ['-include', 'algorithm', '-include', 'cstdint', '-include', 'cstring']

[properties]
needs_exe_wrapper = true

[host_machine]
system = 'windows'
cpu_family = 'aarch64'
cpu = 'aarch64'
endian = 'little'
EOF

# DXVK ships an x86-64 import library for vulkan-1.dll (and looks in lib32 for non-x86_64 hosts): replace both.
printf 'LIBRARY "vulkan-1.dll"\nEXPORTS\n    vkGetInstanceProcAddr\n' > vulkan-1.def
llvm-dlltool -m arm64 -d vulkan-1.def -D vulkan-1.dll -l vulkan-1.lib
llvm-nm vulkan-1.lib | grep -q ' vkGetInstanceProcAddr' || { echo "ARM64 import lib is missing the symbol"; exit 1; }
cp vulkan-1.lib dxvk/lib/vulkan-1.lib
cp vulkan-1.lib dxvk/lib32/vulkan-1.lib

cd dxvk
meson setup --cross-file ../cross-arm64.txt --buildtype release --strip \
  -Denable_d3d9=false -Denable_d3d10=false --prefix "$WORK/prefix" build > ../meson.log 2>&1 || { tail -40 ../meson.log; exit 1; }
ninja -C build -k 0 -j"$(nproc)" > ../build.log 2>&1 || { grep -E 'error:|FAILED' ../build.log | sort | uniq -c | head -40; exit 1; }
cd ..

for dll in d3d11 dxgi; do
  src="$(find dxvk/build -name "$dll.dll" | head -n1)"
  cp "$src" "$OUT/$dll.dll"
  llvm-objdump -f "$OUT/$dll.dll" | grep -q 'coff-arm64' || { echo "$dll.dll is not ARM64"; exit 1; }
done

# Winlator content package: zstd tar with profile.json at the root.
PKG="$WORK/pkg"
rm -rf "$PKG" && mkdir -p "$PKG"
cp "$OUT/d3d11.dll" "$OUT/dxgi.dll" "$PKG/"
cat > "$PKG/profile.json" <<EOF
{
  "type": "DXVK",
  "versionName": "$PKG_VER",
  "versionCode": 1,
  "description": "Unofficial DXVK $VER cross-compiled for Windows ARM64 (Vulkan 1.1 devices)",
  "files": [
    {"source": "d3d11.dll", "target": "\${system32}/d3d11.dll"},
    {"source": "dxgi.dll", "target": "\${system32}/dxgi.dll"}
  ]
}
EOF
tar -C "$PKG" -cf - . | zstd -19 -q -o "$OUT/dxvk-$PKG_VER.wcp" -f
ls -la "$OUT"
sha256sum "$OUT"/*
