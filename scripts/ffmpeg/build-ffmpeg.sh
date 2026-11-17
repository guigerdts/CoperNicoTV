#!/usr/bin/env bash
# Build FFmpeg LGPL subset for CoperNicoTV (E7-T12, S1/S6).
#
# Builds a static libavcodec + libavutil subset with only the decoders of the
# initial matrix (mpeg2video, mpeg4, h263) for the ABIs of the APK. The build
# is reproducible: pinned tarball, pinned SHA-256, no GPL and no nonfree
# components (verified against the generated config.h before compiling).
#
# Output: native/ffmpeg/install/<abi>/lib/{libavcodec.a,libavutil.a}
# Include dirs: native/ffmpeg/install/<abi>/include plus the generated
# config.h directory is copied into include/compat-config by the script.
#
# Requirements: NDK (ANDROID_NDK_HOME or ANDROID_NDK or $ANDROID_HOME/ndk/*),
# curl, tar, xz, make, perl (FFmpeg build tool).
#
# LGPL compliance: the tarball URL and its SHA-256 are pinned below; the
# configure flags contain neither --enable-gpl nor --enable-nonfree, and the
# script fails if the generated configuration marks GPL or nonfree as
# enabled. Redistribution notices live in THIRD_PARTY_NOTICES.md.

set -euo pipefail

FFMPEG_VERSION="7.1.1"
FFMPEG_SHA256="733984395e0dbbe5c046abda2dc49a5544e7e0e1e2366bba849222ae9e3a03b1"
FFMPEG_URL="https://ffmpeg.org/releases/ffmpeg-${FFMPEG_VERSION}.tar.xz"

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
FFMPEG_DIR="$REPO_ROOT/native/ffmpeg"
SRC_DIR="$FFMPEG_DIR/ffmpeg-$FFMPEG_VERSION"
INSTALL_ROOT="$FFMPEG_DIR/install"
ABIS="${FFMPEG_ABIS:-arm64-v8a armeabi-v7a x86_64}"
API_LEVEL="${FFMPEG_API_LEVEL:-26}"

# --- NDK discovery ----------------------------------------------------------
find_ndk() {
    if [[ -n "${ANDROID_NDK_HOME:-}" && -d "$ANDROID_NDK_HOME" ]]; then
        echo "$ANDROID_NDK_HOME"
        return 0
    fi
    if [[ -n "${ANDROID_NDK:-}" && -d "$ANDROID_NDK" ]]; then
        echo "$ANDROID_NDK"
        return 0
    fi
    if [[ -n "${ANDROID_HOME:-}" ]]; then
        local candidate
        candidate="$(ls -d "$ANDROID_HOME"/ndk/* 2>/dev/null | sort -V | tail -1 || true)"
        if [[ -n "$candidate" && -d "$candidate" ]]; then
            echo "$candidate"
            return 0
        fi
    fi
    echo "ERROR: NDK no encontrado (ANDROID_NDK_HOME, ANDROID_NDK o ANDROID_HOME/ndk/*)" >&2
    return 1
}

NDK_DIR="$(find_ndk)"
TOOLCHAIN="$NDK_DIR/toolchains/llvm/prebuilt/linux-x86_64/bin"
echo "== NDK: $NDK_DIR"

# --- Source -----------------------------------------------------------------
mkdir -p "$FFMPEG_DIR"
cd "$FFMPEG_DIR"
if [[ ! -f "ffmpeg-$FFMPEG_VERSION.tar.xz" ]]; then
    echo "== Descargando FFmpeg $FFMPEG_VERSION"
    curl -sSL -o "ffmpeg-$FFMPEG_VERSION.tar.xz" "$FFMPEG_URL"
fi
echo "$FFMPEG_SHA256  ffmpeg-$FFMPEG_VERSION.tar.xz" | sha256sum -c - || {
    echo "ERROR: SHA-256 del tarball no coincide (esperado $FFMPEG_SHA256)" >&2
    exit 1
}
if [[ ! -d "$SRC_DIR" ]]; then
    echo "== Extrayendo fuentes"
    tar -xf "ffmpeg-$FFMPEG_VERSION.tar.xz"
fi

# --- Per-ABI build ----------------------------------------------------------
mkdir -p "$INSTALL_ROOT"
build_abi() {
    local abi="$1"
    local arch cpu cc out
    case "$abi" in
        arm64-v8a)
            arch="aarch64"
            cpu="armv8-a"
            cc="$TOOLCHAIN/aarch64-linux-android${API_LEVEL}-clang"
            ;;
        armeabi-v7a)
            arch="arm"
            cpu="armv7-a"
            cc="$TOOLCHAIN/armv7a-linux-androideabi${API_LEVEL}-clang"
            ;;
        x86_64)
            arch="x86_64"
            cpu="x86_64"
            cc="$TOOLCHAIN/x86_64-linux-android${API_LEVEL}-clang"
            ;;
        *)
            echo "ERROR: ABI no soportada: $abi" >&2
            exit 1
            ;;
    esac
    out="$INSTALL_ROOT/$abi"
    echo "== Compilando $abi (arch=$arch)"
    make -C "$SRC_DIR" distclean >/dev/null 2>&1 || true
    (
        cd "$SRC_DIR"
        ./configure \
            --target-os=android \
            --arch="$arch" \
            --cpu="$cpu" \
            --cc="$cc" \
            --enable-cross-compile \
            --sysroot="$TOOLCHAIN/sysroot" \
            --enable-static \
            --disable-shared \
            --disable-everything \
            --enable-decoder=mpeg2video,mpeg4,h263 \
            --disable-avdevice \
            --disable-avformat \
            --disable-avfilter \
            --disable-swscale \
            --disable-swresample \
            --disable-postproc \
            --disable-network \
            --disable-protocols \
            --disable-demuxers \
            --disable-muxers \
            --disable-programs \
            --disable-doc \
            --disable-debug \
            --disable-autodetect \
            --disable-asm \
            --enable-pic \
            --enable-small \
            --extra-cflags="-ffunction-sections -fdata-sections" || {
            echo "ERROR: configure falló para $abi; config.log:" >&2
            tail -60 config.log >&2 || true
            exit 1
        }
        # LGPL effective configuration check BEFORE compiling (S6/Fase F):
        # the generated configuration must declare GPL and nonfree disabled.
        grep -q "^#define CONFIG_GPL 0" config.h || {
            echo "ERROR: config.h no declara CONFIG_GPL 0 (¿--enable-gpl?) " >&2
            exit 1
        }
        grep -q "^#define CONFIG_NONFREE 0" config.h || {
            echo "ERROR: config.h no declara CONFIG_NONFREE 0 (¿--enable-nonfree?)" >&2
            exit 1
        }
        echo "== Verificación LGPL OK para $abi (sin GPL, sin nonfree)"
        make -j"$(nproc)" libavcodec/libavcodec.a libavutil/libavutil.a
        mkdir -p "$out/lib" "$out/include"
        cp libavcodec/libavcodec.a libavutil/libavutil.a "$out/lib/"
        cp -r libavcodec libavutil "$out/include/" 2>/dev/null || true
        mkdir -p "$out/include/ffmpeg-config"
        cp config.h "$out/include/ffmpeg-config/"
        cp ffbuild/config.h "$out/include/ffmpeg-config/config-ffbuild.h" 2>/dev/null || true
        find "$out/include" -name "*.o" -delete 2>/dev/null || true
    )
    echo "== $abi listo: $(ls "$out/lib")"
}

for abi in $ABIS; do
    build_abi "$abi"
done

echo "== FFmpeg $FFMPEG_VERSION listo en $INSTALL_ROOT para: $ABIS"
