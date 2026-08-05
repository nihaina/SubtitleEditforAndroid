#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
RUNTIME_VERSION="0.41.0-ffmpeg8.1.2-1"
FFMPEG_VERSION="8.1.0-mpv1"
RUNTIME_AAR="${PROJECT_ROOT}/app/libs/mpv-runtime-maven/com/subtitleedit/native/mpv-android-runtime/${RUNTIME_VERSION}/mpv-android-runtime-${RUNTIME_VERSION}.aar"
FFMPEG_AAR="${PROJECT_ROOT}/app/libs/ffmpeg-kit-next-maven/com/arthenica/ffmpeg-kit-next/${FFMPEG_VERSION}/ffmpeg-kit-next-${FFMPEG_VERSION}.aar"

for tool in readelf unzip find awk grep; do
    command -v "${tool}" >/dev/null 2>&1 || {
        echo "error: required tool not found: ${tool}" >&2
        exit 1
    }
done

for archive in "${RUNTIME_AAR}" "${FFMPEG_AAR}"; do
    [[ -f "${archive}" ]] || {
        echo "error: missing AAR: ${archive}" >&2
        exit 1
    }
done

VERIFY_ROOT="$(mktemp -d)"
trap 'rm -rf "${VERIFY_ROOT}"' EXIT
mkdir -p "${VERIFY_ROOT}/runtime" "${VERIFY_ROOT}/ffmpeg"
unzip -q "${RUNTIME_AAR}" -d "${VERIFY_ROOT}/runtime"
unzip -q "${FFMPEG_AAR}" -d "${VERIFY_ROOT}/ffmpeg"

abis=(armeabi-v7a arm64-v8a x86 x86_64)
ffmpeg_libraries=(
    libavcodec.so
    libavdevice.so
    libavfilter.so
    libavformat.so
    libavutil.so
    libswresample.so
    libswscale.so
)

for abi in "${abis[@]}"; do
    runtime_dir="${VERIFY_ROOT}/runtime/jni/${abi}"
    ffmpeg_dir="${VERIFY_ROOT}/ffmpeg/jni/${abi}"
    [[ -f "${runtime_dir}/libmpv.so" ]]
    [[ -f "${runtime_dir}/libsubtitleedit_mpv.so" ]]

    if find "${runtime_dir}" -maxdepth 1 -type f \
        \( -name 'libav*.so' -o -name 'libsw*.so' \) | grep -q .; then
        echo "error: mpv runtime contains duplicate FFmpeg libraries for ${abi}" >&2
        exit 1
    fi

    for library in "${ffmpeg_libraries[@]}"; do
        [[ -f "${ffmpeg_dir}/${library}" ]] || {
            echo "error: missing ${library} for ${abi}" >&2
            exit 1
        }
        grep -Fq "Shared library: [${library}]" \
            < <(readelf -dW "${runtime_dir}/libmpv.so") || {
            echo "error: libmpv does not depend on ${library} for ${abi}" >&2
            exit 1
        }
    done

    grep -Fq 'Shared library: [libmpv.so]' \
        < <(readelf -dW "${runtime_dir}/libsubtitleedit_mpv.so")
    grep -Fq 'Shared library: [libavcodec.so]' \
        < <(readelf -dW "${runtime_dir}/libsubtitleedit_mpv.so")
done

while IFS= read -r -d '' library; do
    while IFS= read -r alignment; do
        (( alignment >= 0x4000 )) || {
            echo "error: ELF load alignment below 16 KB: ${library} (${alignment})" >&2
            exit 1
        }
    done < <(readelf -lW "${library}" | awk '$1 == "LOAD" { print $NF }')
done < <(find \
    "${VERIFY_ROOT}/runtime/jni/arm64-v8a" \
    "${VERIFY_ROOT}/runtime/jni/x86_64" \
    "${VERIFY_ROOT}/ffmpeg/jni/arm64-v8a" \
    "${VERIFY_ROOT}/ffmpeg/jni/x86_64" \
    -type f -name '*.so' -print0)

for variant in debug release; do
    apk_dir="${PROJECT_ROOT}/app/build/outputs/apk/${variant}"
    universal_apks=("${apk_dir}"/*universal*.apk)
    [[ ${#universal_apks[@]} -eq 1 && -f "${universal_apks[0]}" ]] || {
        echo "error: expected one ${variant} universal APK in ${apk_dir}" >&2
        exit 1
    }

    apk_root="${VERIFY_ROOT}/apk-${variant}"
    mkdir -p "${apk_root}"
    unzip -q "${universal_apks[0]}" -d "${apk_root}"
    for license in \
        7zip-LICENSE.txt GPL-3.0.txt THIRD_PARTY_NOTICES.md mpv-android-LICENSE.txt; do
        [[ -f "${apk_root}/assets/licenses/${license}" ]] || {
            echo "error: ${license} missing from ${variant} universal APK" >&2
            exit 1
        }
    done

    for abi in "${abis[@]}"; do
        [[ -f "${apk_root}/lib/${abi}/libmpv.so" ]]
        [[ -f "${apk_root}/lib/${abi}/libsubtitleedit_mpv.so" ]]
        for library in "${ffmpeg_libraries[@]}"; do
            [[ -f "${apk_root}/lib/${abi}/${library}" ]]
        done
    done

    while IFS= read -r -d '' library; do
        while IFS= read -r alignment; do
            (( alignment >= 0x4000 )) || {
                echo "error: APK ELF load alignment below 16 KB: ${library} (${alignment})" >&2
                exit 1
            }
        done < <(readelf -lW "${library}" | awk '$1 == "LOAD" { print $NF }')
    done < <(find "${apk_root}/lib/arm64-v8a" "${apk_root}/lib/x86_64" \
        -type f -name '*.so' -print0)
done

echo "Verified AAR/APK dependencies, unique FFmpeg libraries, licenses, and 64-bit 16 KB ELF alignment."
