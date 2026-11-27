#!/usr/bin/env python3
"""Make DXVK 1.10.x compile for aarch64 (it assumes x86 intrinsics in a few places).

Usage: patch_dxvk.py <dxvk-source-dir>
Each replacement must match exactly once, otherwise the script fails loudly (so a different DXVK
version is not silently half-patched).
"""
import pathlib
import sys

root = pathlib.Path(sys.argv[1])


def patch(rel, old, new):
    p = root / rel
    text = p.read_text()
    if text.count(old) != 1:
        sys.exit(f"{rel}: expected exactly 1 match, found {text.count(old)} for:\n{old}")
    p.write_text(text.replace(old, new))
    print(f"patched {rel}")


X86 = "defined(__x86_64__) || defined(__i386__)"

# 1. util_bit.h: x86intrin.h only exists on x86.
patch(
    "src/util/util_bit.h",
    "#include <x86intrin.h>\n",
    f"#if {X86}\n#include <x86intrin.h>\n#endif\n",
)

# 2. tzcnt: the generic branch is x86 inline asm; use a builtin on ARM.
patch(
    "src/util/util_bit.h",
    "    #elif defined(__BMI__)\n    return __tzcnt_u32(n);\n",
    "    #elif defined(__BMI__)\n    return __tzcnt_u32(n);\n"
    "    #elif defined(__aarch64__)\n    return n != 0 ? __builtin_ctz(n) : 32;\n",
)

# 3. bcmpeq: SSE2 compare -> memcmp on non-x86.
patch(
    "src/util/util_bit.h",
    "    #if defined(__GNUC__) || defined(__clang__) || defined(_MSC_VER)\n    auto ai = reinterpret_cast<const __m128i*>(a);",
    f"    #if ({X86} || defined(_MSC_VER)) && (defined(__GNUC__) || defined(__clang__) || defined(_MSC_VER))\n"
    "    auto ai = reinterpret_cast<const __m128i*>(a);",
)

# 4. spin loop pause.
patch(
    "src/util/sync/sync_spinlock.h",
    "        _mm_pause();\n",
    f"        #if {X86}\n        _mm_pause();\n        #elif defined(__aarch64__)\n"
    '        __asm__ __volatile__("yield");\n        #endif\n',
)

# 5. replaceNaN: SSE -> scalar.
patch(
    "src/util/util_vector.h",
    "    __m128 value = _mm_load_ps(a.data);\n"
    "    __m128 mask  = _mm_cmpeq_ps(value, value);\n"
    "           value = _mm_and_ps(value, mask);\n"
    "    _mm_store_ps(result.data, value);\n",
    "    for (int i = 0; i < 4; i++)\n"
    "      result.data[i] = (a.data[i] == a.data[i]) ? a.data[i] : 0.0f;\n",
)
