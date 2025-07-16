#!/bin/bash

set -e

# ========== Android FFmpeg 多架构编译脚本 =================

# 依赖工具检查
REQUIRED_TOOLS=(gcc g++ make perl pkg-config zip awk sed grep bash)
MISSING_TOOLS=()
for tool in "${REQUIRED_TOOLS[@]}"; do
    if ! command -v "$tool" >/dev/null 2>&1; then
        MISSING_TOOLS+=("$tool")
    fi
done

if [ ${#MISSING_TOOLS[@]} -ne 0 ]; then
    echo "缺少编译工具: ${MISSING_TOOLS[*]}"
    echo "请先安装，例如: sudo apt install ${MISSING_TOOLS[*]}"
    exit 1
fi

# NDK 配置
NDK_HOME=/home/cf/research/android-ndk-r22b
NDK_HOST_PLATFORM=linux-x86_64  # Linux: linux-x86_64, macOS: darwin-x86_64

if [ ! -d "${NDK_HOME}/toolchains/llvm/prebuilt/${NDK_HOST_PLATFORM}" ]; then
    echo "错误：NDK 工具链不存在：${NDK_HOME}/toolchains/llvm/prebuilt/${NDK_HOST_PLATFORM}"
    exit 1
fi

# 输出路径
PREFIX=$(pwd)/android-build-out
rm -rf "$PREFIX"

# 检查 FFmpeg 源码路径
if [ ! -f "./configure" ]; then
    echo "错误: 请在 FFmpeg 源码目录下运行此脚本（找不到 ./configure）"
    exit 1
fi

# 通用配置选项
COMMON_OPTIONS="
    --disable-doc
    --enable-shared
    --disable-static
    --disable-symver
    --enable-gpl
    --disable-ffmpeg
    --disable-ffplay
    --disable-ffprobe
    --enable-cross-compile
    --enable-small
    --disable-programs
"

# 编译函数
build_android() {
    echo ""
    echo "========== 编译 ${ANDROID_ABI} =========="

    SYSROOT="${NDK_HOME}/toolchains/llvm/prebuilt/${NDK_HOST_PLATFORM}/sysroot"
    OUT_DIR=${PREFIX}/${ANDROID_ABI}
    ABI_LIBS_DIR=${PREFIX}/libs/${ANDROID_ABI}
    mkdir -p "${ABI_LIBS_DIR}"

    # 💡 为 x86/x86_64 禁用所有汇编相关模块
    if [[ "${ARCH_TYPE}" == "x86" || "${ARCH_TYPE}" == "x86_64" ]]; then
        EXTRA_OPTIONS="--disable-asm --disable-x86asm"
        echo "⚠️  ${ANDROID_ABI} 架构已禁用汇编加速（避免 rgb2rgb.c 编译失败）"
    else
        EXTRA_OPTIONS=""
    fi

    ./configure \
        --prefix="${OUT_DIR}" \
        --arch="${ARCH_TYPE}" \
        --cpu="${CPU_TYPE}" \
        --target-os=android \
        --cross-prefix="${CROSS_PREFIX}" \
        --cc="${CC}" \
        --cxx="${CXX}" \
        --sysroot="${SYSROOT}" \
        ${COMMON_OPTIONS} \
        ${EXTRA_OPTIONS}

    if [ $? -ne 0 ]; then
        echo "❌ 配置失败: ${ANDROID_ABI}"
        exit 1
    fi

    make clean
    make -j$(nproc)
    make install

    cp "${OUT_DIR}/lib/"*.so "${ABI_LIBS_DIR}/"
    echo "✅ ${ANDROID_ABI} 编译完成"
}

# 架构列表
ARCHS=("armeabi-v7a" "arm64-v8a" "x86" "x86_64")

# 编译每个架构
for ABI in "${ARCHS[@]}"; do
    case "$ABI" in
        armeabi-v7a)
            ANDROID_ABI=armeabi-v7a
            ARCH_TYPE=arm
            CPU_TYPE=armv7-a
            API=16
            CROSS_PREFIX="${NDK_HOME}/toolchains/llvm/prebuilt/${NDK_HOST_PLATFORM}/bin/arm-linux-androideabi-"
            CC="${NDK_HOME}/toolchains/llvm/prebuilt/${NDK_HOST_PLATFORM}/bin/armv7a-linux-androideabi${API}-clang"
            CXX="${NDK_HOME}/toolchains/llvm/prebuilt/${NDK_HOST_PLATFORM}/bin/armv7a-linux-androideabi${API}-clang++"
            ;;
        arm64-v8a)
            ANDROID_ABI=arm64-v8a
            ARCH_TYPE=aarch64
            CPU_TYPE=armv8-a
            API=21
            CROSS_PREFIX="${NDK_HOME}/toolchains/llvm/prebuilt/${NDK_HOST_PLATFORM}/bin/aarch64-linux-android-"
            CC="${NDK_HOME}/toolchains/llvm/prebuilt/${NDK_HOST_PLATFORM}/bin/aarch64-linux-android${API}-clang"
            CXX="${NDK_HOME}/toolchains/llvm/prebuilt/${NDK_HOST_PLATFORM}/bin/aarch64-linux-android${API}-clang++"
            ;;
        x86)
            ANDROID_ABI=x86
            ARCH_TYPE=x86
            CPU_TYPE=i686
            API=16
            CROSS_PREFIX="${NDK_HOME}/toolchains/llvm/prebuilt/${NDK_HOST_PLATFORM}/bin/i686-linux-android-"
            CC="${NDK_HOME}/toolchains/llvm/prebuilt/${NDK_HOST_PLATFORM}/bin/i686-linux-android${API}-clang"
            CXX="${NDK_HOME}/toolchains/llvm/prebuilt/${NDK_HOST_PLATFORM}/bin/i686-linux-android${API}-clang++"
            ;;
        x86_64)
            ANDROID_ABI=x86_64
            ARCH_TYPE=x86_64
            CPU_TYPE=x86-64
            API=21
            CROSS_PREFIX="${NDK_HOME}/toolchains/llvm/prebuilt/${NDK_HOST_PLATFORM}/bin/x86_64-linux-android-"
            CC="${NDK_HOME}/toolchains/llvm/prebuilt/${NDK_HOST_PLATFORM}/bin/x86_64-linux-android${API}-clang"
            CXX="${NDK_HOME}/toolchains/llvm/prebuilt/${NDK_HOST_PLATFORM}/bin/x86_64-linux-android${API}-clang++"
            ;;
    esac

    build_android
done

# ========== 打包 ==========

cd "${PREFIX}"
ZIP_FILE_PATH="${PREFIX}.zip"
[ -f "${ZIP_FILE_PATH}" ] && rm -f "${ZIP_FILE_PATH}"

echo ""
echo "开始压缩为 ${ZIP_FILE_PATH}..."
zip -r "${ZIP_FILE_PATH}" ./* >/dev/null
echo "✅ 编译并压缩完成：${ZIP_FILE_PATH}"
