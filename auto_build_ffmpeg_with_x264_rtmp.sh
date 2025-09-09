# 自动下载编译ffmpeg，且支持x264和rtmp推流
#!/usr/bin/env bash
set -Eeuo pipefail

# ==== debug ====
DEBUG=0   # 1 开启 bash -x
if [[ "$DEBUG" -eq 1 ]]; then set -x; fi
trap 'ec=$(($?)); echo "[ERR] ${BASH_SOURCE[0]}:${LINENO}: $BASH_COMMAND (exit $ec)"; exit $ec' ERR
trap 'echo "[INT] Interrupted"; exit 130' INT

step() { echo; echo "---- [step] $* ----"; }

# ==============================================================================
# Android FFmpeg + x264 build (manual paths, RTMP-ready, x86 fixes + packaging)
# ==============================================================================

# --------------------------- Manual configuration ------------------------------
SDK_DIR="/absolute/path/to/Android/Sdk"           # optional
NDK_DIR="/home/cf/research/android-ndk-r22b"      # required

# Tool binaries (absolute paths)
GIT_BIN="/usr/bin/git"
MAKE_BIN="/usr/bin/make"
PKG_CONFIG_BIN="/usr/bin/pkg-config"
NASM_BIN="/usr/bin/nasm"      # for x86_64 asm; x86 强制禁用汇编；留空则不启用 x86_64 汇编

# Override host tag if needed: linux-x86_64 | darwin-x86_64 | darwin-arm64
HOST_TAG_OVERRIDE=""

# ------------------------------ Build presets ----------------------------------
BUILD_TYPE="${1:-shared}"   # shared | static
shift || true

ABIS="arm64-v8a,armeabi-v7a,x86,x86_64"
API=21
FFMPEG_REF="n6.1.1"
X264_REF="master"
WITH_PROGRAMS=0
DO_CLEAN=0
REFRESH_SRC=0
JOBS=4

# Features
FORCE_X264_UNVERSIONED_SONAME=1
LINK_X264_STATIC_INTO_FFMPEG=0

# ----------------------------- Parse CLI options -------------------------------
while [[ $# -gt 0 ]]; do
  case "$1" in
    --abis) ABIS="$2"; shift 2 ;;
    --api) API="$2"; shift 2 ;;
    --ffmpeg) FFMPEG_REF="$2"; shift 2 ;;
    --x264) X264_REF="$2"; shift 2 ;;
    --with-programs) WITH_PROGRAMS=1; shift ;;
    --clean) DO_CLEAN=1; shift ;;
    --refresh-src) REFRESH_SRC=1; shift ;;
    --jobs) JOBS="$2"; shift 2 ;;
    --host-tag) HOST_TAG_OVERRIDE="$2"; shift 2 ;;
    --x264-unversioned) FORCE_X264_UNVERSIONED_SONAME="$2"; shift 2 ;;
    --x264-static-in-ffmpeg) LINK_X264_STATIC_INTO_FFMPEG="$2"; shift 2 ;;
    --debug) DEBUG="$2"; shift 2 ;;
    -*) echo "Unknown option: $1" >&2; exit 1 ;;
    *) echo "Unexpected arg: $1" >&2; exit 1 ;;
  esac
done
if [[ "$BUILD_TYPE" != "shared" && "$BUILD_TYPE" != "static" ]]; then
  echo "Usage: $0 [shared|static] [--abis ...] [--api ...] [--ffmpeg ...] [--x264 ...] [--with-programs] [--clean] [--refresh-src] [--jobs N] [--host-tag TAG]"; exit 1
fi

# ------------------------------ Env and paths ----------------------------------
[[ -d "$NDK_DIR" ]] || { echo "[error] NDK_DIR not found: $NDK_DIR"; exit 1; }

detect_host_tag() {
  if [[ -n "$HOST_TAG_OVERRIDE" ]]; then echo "$HOST_TAG_OVERRIDE"; return; fi
  case "$(uname -s)" in
    Darwin) [[ -d "$NDK_DIR/toolchains/llvm/prebuilt/darwin-arm64" ]] && echo "darwin-arm64" || echo "darwin-x86_64" ;;
    Linux)  echo "linux-x86_64" ;;
    *) echo "[error] Unsupported host OS" >&2; exit 1 ;;
  esac
}
HOST_TAG="$(detect_host_tag)"
TOOLCHAIN="$NDK_DIR/toolchains/llvm/prebuilt/$HOST_TAG"
[[ -d "$TOOLCHAIN" ]] || { echo "[error] Toolchain not found: $TOOLCHAIN"; exit 1; }

ROOT="$(pwd)"
SRC="$ROOT/src"
BUILD="$ROOT/build"
OUT="$ROOT/out"
JNI_LIBS_DIR="$ROOT/jniLibs"
INC_ROOT="$ROOT/ffmpeg/include"
mkdir -p "$SRC" "$BUILD" "$OUT"

require_abs_exec() {
  local bin="$1" name="$2"
  [[ "${bin:0:1}" == "/" ]] || { echo "[error] $name path must be absolute: $bin"; exit 1; }
  [[ -x "$bin" ]] || { echo "[error] $name not executable: $bin"; exit 1; }
}
require_abs_exec "$GIT_BIN" "git"
require_abs_exec "$MAKE_BIN" "make"
require_abs_exec "$PKG_CONFIG_BIN" "pkg-config"
if [[ -n "${NASM_BIN:-}" ]]; then require_abs_exec "$NASM_BIN" "nasm"; fi

if [[ "$DO_CLEAN" -eq 1 ]]; then rm -rf "$BUILD" "$OUT" "$JNI_LIBS_DIR" "$INC_ROOT"; echo "[clean] removed build/, out/, jniLibs/, ffmpeg/include/"; fi

echo "=== Config ==="
echo "NDK:          $NDK_DIR"
echo "Toolchain:    $TOOLCHAIN"
echo "Host tag:     $HOST_TAG"
echo "API Level:    $API"
echo "ABIs:         $ABIS"
echo "FFmpeg ref:   $FFMPEG_REF"
echo "x264 ref:     $X264_REF"
echo "Programs:     $([[ $WITH_PROGRAMS -eq 1 ]] && echo yes || echo no)"
echo "Build Type:   $BUILD_TYPE"
echo "Jobs:         $JOBS"
echo "NASM:         ${NASM_BIN:-<not set>}"
echo "=============="

# ---------------------------- Git fetch helpers --------------------------------
fetch_checkout() {
  local name="$1" dir="$2" ref="$3"; shift 3
  local repos=("$@")
  rm -rf "$dir"
  local ok=0
  for url in "${repos[@]}"; do
    echo "[fetch] $name from $url ..."
    if "$GIT_BIN" clone "$url" "$dir"; then
      pushd "$dir" >/dev/null
      "$GIT_BIN" fetch --all --tags --prune
      if "$GIT_BIN" show-ref --verify --quiet "refs/remotes/origin/$ref"; then "$GIT_BIN" checkout -f -B "$ref" "origin/$ref"; ok=1; popd >/dev/null; break
      elif "$GIT_BIN" show-ref --tags --verify --quiet "refs/tags/$ref"; then "$GIT_BIN" checkout -f -B "$ref" "refs/tags/$ref"; ok=1; popd >/dev/null; break
      else echo "[warn] ref '$ref' not found in $url"; popd >/dev/null; fi
    else echo "[warn] clone failed: $url"; fi
    rm -rf "$dir"
  done
  if [[ "$ok" -ne 1 ]]; then
    echo "[warn] '$ref' not found, fallback to master/main"
    for url in "${repos[@]}"; do
      echo "[fetch] fallback from $url ..."
      if "$GIT_BIN" clone "$url" "$dir"; then
        pushd "$dir" >/dev/null
        "$GIT_BIN" fetch --all --tags --prune
        if "$GIT_BIN" show-ref --verify --quiet "refs/remotes/origin/master"; then "$GIT_BIN" checkout -f -B master origin/master; ok=1; popd >/dev/null; break
        elif "$GIT_BIN" show-ref --verify --quiet "refs/remotes/origin/main"; then "$GIT_BIN" checkout -f -B main origin/main; ok=1; popd >/dev/null; break
        fi
        popd >/dev/null
      fi
      rm -rf "$dir"
    done
  fi
  if [[ "$ok" -ne 1 ]]; then echo "[error] Unable to fetch '$name' with ref '$ref'"; exit 1; fi
}

X264_REPOS=("https://code.videolan.org/videolan/x264.git" "https://github.com/mirror/x264.git")
FFMPEG_REPOS=("https://github.com/FFmpeg/FFmpeg.git")

step "fetch sources"
if [[ "$REFRESH_SRC" -eq 1 ]]; then rm -rf "$SRC/x264" "$SRC/ffmpeg"; fi
[[ -d "$SRC/x264" ]] || fetch_checkout "x264" "$SRC/x264" "$X264_REF" "${X264_REPOS[@]}"
[[ -d "$SRC/ffmpeg" ]] || fetch_checkout "ffmpeg" "$SRC/ffmpeg" "$FFMPEG_REF" "${FFMPEG_REPOS[@]}"
if [[ ! -f "$SRC/x264/Makefile" ]]; then echo "[warn] x264 Makefile missing; refetch..."; rm -rf "$SRC/x264"; fetch_checkout "x264" "$SRC/x264" "$X264_REF" "${X264_REPOS[@]}"; fi

# ------------------------------ ABI setup --------------------------------------
if [[ "$BUILD_TYPE" == "shared" ]]; then
  ENABLE_SHARED_FLAG="--enable-shared --disable-static"
  PKGCONF_STATIC=""
else
  ENABLE_SHARED_FLAG="--enable-static --disable-shared"
  PKGCONF_STATIC="--static"
fi
X264_CONF_EXTRA="--disable-opencl --disable-lavf --disable-ffms --disable-avs"

get_triples() {
  case "$1" in
    arm64-v8a) echo "aarch64-linux-android|aarch64-linux-android|arm64|armv8-a|arch-arm64" ;;
    armeabi-v7a) echo "armv7a-linux-androideabi|arm-linux-androideabi|arm|armv7-a|arch-arm" ;;
    x86) echo "i686-linux-android|i686-linux-android|x86|i686|arch-x86" ;;
    x86_64) echo "x86_64-linux-android|x86_64-linux-android|x86_64|x86-64|arch-x86_64" ;;
    *) echo "[error] Unsupported ABI: $1" >&2; exit 1 ;;
  esac
}
find_tool() { local p="$1" f="${2:-}"; if [[ -x "$p" ]]; then echo "$p"; elif [[ -n "$f" && -x "$f" ]]; then echo "$f"; else echo ""; fi; }

abi_setup() {
  local abi="$1"
  local triples; IFS='|' read -r TRIPLE X264_HOST FFMPEG_ARCH FFMPEG_CPU LEGACY_ARCH_DIR <<<"$(get_triples "$abi")"

  local unified_sysroot="$TOOLCHAIN/sysroot"
  if [[ -d "$unified_sysroot" ]]; then SYSROOT="$unified_sysroot"
  else
    local legacy_sysroot="$NDK_DIR/platforms/android-$API/$LEGACY_ARCH_DIR"
    [[ -d "$legacy_sysroot" ]] || { echo "[error] No sysroot"; exit 1; }
    SYSROOT="$legacy_sysroot"
  fi

  local CC_WRAP1="$TOOLCHAIN/bin/${TRIPLE}${API}-clang"
  local CXX_WRAP1="$TOOLCHAIN/bin/${TRIPLE}${API}-clang++"
  local CC_WRAP2="$TOOLCHAIN/bin/${TRIPLE}-clang"
  local CXX_WRAP2="$TOOLCHAIN/bin/${TRIPLE}-clang++"
  local CLANG_BIN="$TOOLCHAIN/bin/clang"
  local CLANGXX_BIN="$TOOLCHAIN/bin/clang++"

  if [[ -x "$CC_WRAP1" && -x "$CXX_WRAP1" ]]; then CC="$CC_WRAP1"; CXX="$CXX_WRAP1"
  elif [[ -x "$CC_WRAP2" && -x "$CXX_WRAP2" ]]; then CC="$CC_WRAP2"; CXX="$CXX_WRAP2"
  elif [[ -x "$CLANG_BIN" && -x "$CLANGXX_BIN" ]]; then CC="$CLANG_BIN --target=${TRIPLE}${API} --sysroot=$SYSROOT"; CXX="$CLANGXX_BIN --target=${TRIPLE}${API} --sysroot=$SYSROOT"
  else echo "[error] No suitable clang"; exit 1; fi

  AS_CC="$CC"; LD="$CC"

  local LLVM_AR="$TOOLCHAIN/bin/llvm-ar"
  local LLVM_NM="$TOOLCHAIN/bin/llvm-nm"
  local LLVM_RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
  local LLVM_STRIP="$TOOLCHAIN/bin/llvm-strip"
  local GCC_PREFIX_DIR="" GCC_TRIPLE_BIN=""
  case "$abi" in
    arm64-v8a) GCC_PREFIX_DIR="$NDK_DIR/toolchains/aarch64-linux-android-4.9/prebuilt/$HOST_TAG/bin"; GCC_TRIPLE_BIN="aarch64-linux-android" ;;
    armeabi-v7a) GCC_PREFIX_DIR="$NDK_DIR/toolchains/arm-linux-androideabi-4.9/prebuilt/$HOST_TAG/bin"; GCC_TRIPLE_BIN="arm-linux-androideabi" ;;
    x86) GCC_PREFIX_DIR="$NDK_DIR/toolchains/x86-4.9/prebuilt/$HOST_TAG/bin"; GCC_TRIPLE_BIN="i686-linux-android" ;;
    x86_64) GCC_PREFIX_DIR="$NDK_DIR/toolchains/x86_64-4.9/prebuilt/$HOST_TAG/bin"; GCC_TRIPLE_BIN="x86_64-linux-android" ;;
  esac
  AR="$(find_tool "$LLVM_AR" "$GCC_PREFIX_DIR/${GCC_TRIPLE_BIN}-ar")"
  NM="$(find_tool "$LLVM_NM" "$GCC_PREFIX_DIR/${GCC_TRIPLE_BIN}-nm")"
  RANLIB="$(find_tool "$LLVM_RANLIB" "$GCC_PREFIX_DIR/${GCC_TRIPLE_BIN}-ranlib")"
  STRIP_BIN="$(find_tool "$LLVM_STRIP" "$GCC_PREFIX_DIR/${GCC_TRIPLE_BIN}-strip")"
  [[ -n "$AR" && -n "$NM" && -n "$RANLIB" && -n "$STRIP_BIN" ]] || { echo "[error] Missing binutils"; exit 1; }

  COMMON_CFLAGS="-fPIC -O3 -fstrict-aliasing -pipe -D__ANDROID_API__=${API}"
  COMMON_LDFLAGS="-lc -lm -ldl -llog"

  local LIB_API_DIR="$TOOLCHAIN/sysroot/usr/lib/$TRIPLE/$API"
  local LIB_TRIPLE_DIR="$TOOLCHAIN/sysroot/usr/lib/$TRIPLE"
  local LIB_USR_DIR="$TOOLCHAIN/sysroot/usr/lib"
  LINKER_RPATHLINK="-Wl,-rpath-link=$LIB_API_DIR -Wl,-rpath-link=$LIB_TRIPLE_DIR -Wl,-rpath-link=$LIB_USR_DIR"

  case "$abi" in
    arm64-v8a) COMMON_CFLAGS="$COMMON_CFLAGS -march=armv8-a"; FFMPEG_NEON_FLAG="--enable-neon" ;;
    armeabi-v7a) COMMON_CFLAGS="$COMMON_CFLAGS -march=armv7-a -mfpu=neon -mfloat-abi=softfp"; FFMPEG_NEON_FLAG="--enable-neon" ;;
    x86|x86_64) FFMPEG_NEON_FLAG="" ;;
  esac

  export SYSROOT AR NM RANLIB STRIP_BIN CC CXX AS_CC LD LINKER_RPATHLINK
  export COMMON_CFLAGS COMMON_LDFLAGS
  export FFMPEG_ARCH FFMPEG_CPU X264_HOST FFMPEG_NEON_FLAG
}

# ------------------------------ Helpers ----------------------------------------
sanitize_x264_src() {
  local removed=0
  for f in "$SRC/x264/config.h" "$SRC/x264/x264_config.h" "$SRC/x264/config.mak"; do
    if [[ -f "$f" ]]; then rm -f "$f"; removed=1; fi
  done
  if [[ "$removed" -eq 1 ]]; then
    echo "[x264] Removed in-tree config leftovers (config.h/x264_config.h/config.mak)"
  fi
}
check_cc_works() {
  local cc="$1" bdir="$2"
  echo 'int main(){return 0;}' > "$bdir/cc_test.c"
  eval "$cc -fPIE -o '$bdir/cc_test' '$bdir/cc_test.c' -pie $LINKER_RPATHLINK >/dev/null 2>&1"
  rm -f "$bdir/cc_test.c" "$bdir/cc_test" || true
}
nasm_version_ok() {
  local bin="${NASM_BIN:-}"
  [[ -n "$bin" && -x "$bin" ]] || return 1
  local v="$("$bin" -v 2>&1 | sed -n '1s/.*version KATEX_INLINE_OPEN[0-9.]*KATEX_INLINE_CLOSE.*/\1/p')" || true
  [[ -z "$v" ]] && return 1
  local major="${v%%.*}"; local rest="${v#*.}"; local minor="${rest%%.*}"; [[ -z "$minor" ]] && minor=0
  if (( major > 2 )) || (( major == 2 && minor >= 13 )); then return 0; else return 1; fi
}
force_unversioned_x264_soname() {
  local bdir="$1"
  if [[ "$FORCE_X264_UNVERSIONED_SONAME" -eq 1 ]]; then
    for f in "$bdir/config.mak" "$bdir/Makefile"; do
      [[ -f "$f" ]] || continue
      sed -Ei 's/^(SONAME\s*=\s*).*/\1libx264\.so/' "$f" || true
      sed -Ei 's/^(FULLNAME\s*=\s*).*/\1libx264\.so/' "$f" || true
      sed -Ei 's/libx264\.so\.[0-9]+/libx264.so/g' "$f" || true
    done
  fi
}

# ------------------------------ Build x264 -------------------------------------
build_x264() {
  local abi="$1"
  step "x264: $abi"
  abi_setup "$abi"
  sanitize_x264_src

  local prefix="$OUT/$abi"
  local bdir="$BUILD/x264-$abi"
  mkdir -p "$bdir" "$prefix"
  check_cc_works "$CC" "$bdir"

  local X264_ASM_OPTS=()
  local AS_FOR_X264="$AS_CC"

  if [[ "$abi" == "x86" ]]; then
    echo "[x264] Forcing --disable-asm on x86 to avoid clang inline asm issues"
    X264_ASM_OPTS+=(--disable-asm)
  elif [[ "$abi" == "x86_64" ]]; then
    if nasm_version_ok; then
      echo "[x264] Using NASM: $NASM_BIN"
      AS_FOR_X264="$NASM_BIN"
    else
      echo "[x264] NASM missing/old for x86_64, falling back to --disable-asm"
      X264_ASM_OPTS+=(--disable-asm)
    fi
  fi

  pushd "$bdir" >/dev/null

  local X264_STRIP_OBJ=":"         # disable object-level strip
  local X264_STRIP_BIN="$STRIP_BIN"

  PKG_CONFIG_PATH="" PKG_CONFIG_LIBDIR="" \
  CC="$CC" CXX="$CXX" AR="$AR" AS="$AS_FOR_X264" LD="$LD" RANLIB="$RANLIB" STRIP="$X264_STRIP_OBJ" \
  CFLAGS="$COMMON_CFLAGS" LDFLAGS="$COMMON_LDFLAGS" \
  "$SRC/x264/configure" \
    --prefix="$prefix" \
    --disable-cli \
    --enable-pic \
    --enable-shared \
    --enable-static \
    "${X264_ASM_OPTS[@]}" \
    $X264_CONF_EXTRA \
    --host="$X264_HOST" \
    --extra-cflags="$COMMON_CFLAGS" \
    --extra-ldflags="$COMMON_LDFLAGS $LINKER_RPATHLINK -Wl,-z,relro,-z,now"

  force_unversioned_x264_soname "$bdir"

  "$MAKE_BIN" -j"$JOBS"
  "$MAKE_BIN" install

  if [[ "$BUILD_TYPE" == "shared" && "$LINK_X264_STATIC_INTO_FFMPEG" -eq 0 ]]; then
    find "$prefix/lib" -name "libx264.so*" -exec "$X264_STRIP_BIN" --strip-unneeded {} \; || true
  fi
  if [[ "$LINK_X264_STATIC_INTO_FFMPEG" -eq 1 ]]; then
    rm -f "$prefix/lib/libx264.so" "$prefix/lib/libx264.so."* || true
  fi
  popd >/dev/null
}

# ------------------------------ Build FFmpeg -----------------------------------
build_ffmpeg() {
  local abi="$1"
  step "FFmpeg: $abi"
  abi_setup "$abi"

  local prefix="$OUT/$abi"
  local bdir="$BUILD/ffmpeg-$abi"
  mkdir -p "$bdir" "$prefix"
  check_cc_works "$CC" "$bdir"

  pushd "$bdir" >/dev/null

  export PKG_CONFIG="$PKG_CONFIG_BIN"
  export PKG_CONFIG_LIBDIR="$prefix/lib/pkgconfig"
  export PKG_CONFIG_PATH="$prefix/lib/pkgconfig"

  local EXTRA_CFLAGS="$COMMON_CFLAGS -fPIC -fPIE -I$prefix/include"
  local EXTRA_LDFLAGS="$COMMON_LDFLAGS -fPIE -pie -L$prefix/lib $LINKER_RPATHLINK"

  local PROGRAM_FLAGS="--disable-programs"
  [[ "$WITH_PROGRAMS" -eq 1 ]] && PROGRAM_FLAGS="--enable-programs"

  local PKGCONF_FLAGS="$PKGCONF_STATIC"
  [[ "$LINK_X264_STATIC_INTO_FFMPEG" -eq 1 ]] && PKGCONF_FLAGS="--static"

  # x86 专项：禁用内联汇编 + x86 汇编，避免 rgb2rgb_template.c 等内联汇编报错
  local FFMPEG_ASM_OPTS=()
  local X86ASMEXE_OPT=""
  if [[ "$abi" == "x86" ]]; then
    FFMPEG_ASM_OPTS+=(--disable-asm --disable-inline-asm --disable-x86asm)
  elif [[ "$abi" == "x86_64" ]]; then
    if nasm_version_ok; then
      X86ASMEXE_OPT="--x86asmexe=$NASM_BIN"
    else
      FFMPEG_ASM_OPTS+=(--disable-x86asm)
    fi
  fi

  "$SRC/ffmpeg/configure" \
    --prefix="$prefix" \
    --pkg-config="$PKG_CONFIG" \
    --pkg-config-flags="$PKGCONF_FLAGS" \
    --target-os=android \
    --arch="$FFMPEG_ARCH" \
    --cpu="$FFMPEG_CPU" \
    --enable-cross-compile \
    --cc="$CC" --cxx="$CXX" \
    --ar="$AR" --as="$AS_CC" --ld="$LD" --nm="$NM" --ranlib="$RANLIB" --strip="$STRIP_BIN" \
    --sysroot="$SYSROOT" \
    --extra-cflags="$EXTRA_CFLAGS" \
    --extra-ldflags="$EXTRA_LDFLAGS" \
    $ENABLE_SHARED_FLAG \
    $PROGRAM_FLAGS \
    --disable-doc \
    --enable-pic \
    --enable-gpl \
    --enable-libx264 \
    --enable-asm \
    $FFMPEG_NEON_FLAG \
    --enable-network \
    --enable-muxer=flv \
    "${FFMPEG_ASM_OPTS[@]}" \
    $X86ASMEXE_OPT

  "$MAKE_BIN" -j"$JOBS"
  "$MAKE_BIN" install

  if [[ "$BUILD_TYPE" == "shared" ]]; then
    find "$prefix/lib" -name "libav*.so" -exec "$STRIP_BIN" --strip-unneeded {} \; || true
  fi
  popd >/dev/null
}

# ------------------------------ Packaging --------------------------------------
package_outputs() {
  step "package jniLibs and headers"
  rm -rf "$JNI_LIBS_DIR" "$INC_ROOT"
  mkdir -p "$JNI_LIBS_DIR" "$INC_ROOT"

  IFS=',' read -r -a ABIS_ARR <<< "$ABIS"
  for abi in "${ABIS_ARR[@]}"; do
    abi="$(echo "$abi" | xargs)"
    local_lib="$OUT/$abi/lib"
    local_inc="$OUT/$abi/include"

    # jniLibs: 仅 shared 模式打包 .so
    if [[ "$BUILD_TYPE" == "shared" ]]; then
      mkdir -p "$JNI_LIBS_DIR/$abi"
      # 拷贝该 ABI 下的所有 .so
      if [[ -d "$local_lib" ]]; then
        find "$local_lib" -maxdepth 1 -type f -name "*.so*" -exec cp -f {} "$JNI_LIBS_DIR/$abi/" \;
      fi
    else
      # static 模式：可选生成 staticLibs 目录
      mkdir -p "$ROOT/staticLibs/$abi"
      if [[ -d "$local_lib" ]]; then
        find "$local_lib" -maxdepth 1 -type f -name "*.a" -exec cp -f {} "$ROOT/staticLibs/$abi/" \;
      fi
    fi

    # 头文件：按 ABI 收纳
    if [[ -d "$local_inc" ]]; then
      mkdir -p "$INC_ROOT/$abi"
      # 保留子目录结构（libavcodec/... x264.h 等）
      cp -a "$local_inc/." "$INC_ROOT/$abi/"
    fi
  done

  echo "[ok] packaged:"
  echo " - jniLibs/: $( [[ $BUILD_TYPE == shared ]] && echo 'shared libs ready' || echo 'skipped (static mode)')"
  echo " - ffmpeg/include/: per-ABI headers"
  if [[ "$BUILD_TYPE" != "shared" ]]; then
    echo " - staticLibs/: .a per ABI"
  fi
}

# --------------------------------- Main ----------------------------------------
IFS=',' read -r -a ABIS_ARR <<< "$ABIS"

step "build loop"
for abi in "${ABIS_ARR[@]}"; do
  abi="$(echo "$abi" | xargs)"
  build_x264 "$abi"
  build_ffmpeg "$abi"
  echo "=== Done $abi -> $OUT/$abi ==="
done

package_outputs

echo
echo "All done!"
echo "JNI libs:     $JNI_LIBS_DIR/<abi>/*.so"
echo "Headers:      $INC_ROOT/<abi>/(libav*/ x264.h x264_config.h ...)"
if [[ "$BUILD_TYPE" != "shared" ]]; then
  echo "Static libs:  $ROOT/staticLibs/<abi>/*.a"
fi
echo "Original install roots: $OUT/<abi>/lib and $OUT/<abi>/include"
