#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
FFMPEG_KIT_DIR="${FFMPEG_KIT_DIR:-/home/nihaina/src/subtitleedit-ffmpeg-kit-next}"
WORK_DIR="${SUBTITLEEDIT_MPV_WORK_DIR:-${HOME}/subtitleedit-mpv-build}"
MPV_ANDROID_COMMIT="20a3fa526fac6d3fe267aee0d4c349893fee65a3"
MPV_VERSION="0.41.0"
RUNTIME_VERSION="0.41.0-ffmpeg8.1.2-1"
FFMPEG_ARTIFACT_VERSION="8.1.0-mpv1"

require_tool() {
    command -v "$1" >/dev/null 2>&1 || {
        echo "error: required tool not found: $1" >&2
        exit 1
    }
}

for tool in curl tar sed meson ninja pkg-config zip unzip jar; do
    require_tool "$tool"
done

if [[ -z "${ANDROID_NDK_ROOT:-}" || ! -x "${ANDROID_NDK_ROOT}/ndk-build" ]]; then
    echo "error: ANDROID_NDK_ROOT must point to an Android NDK with ndk-build" >&2
    echo "run this script inside the FFmpegKit android-r27d Nix shell" >&2
    exit 1
fi

download_source() {
    local name="$1"
    local url="$2"
    local archive_type="$3"
    local destination="${WORK_DIR}/sources/${name}"
    if [[ -f "${destination}/.subtitleedit-ready" ]]; then
        return
    fi
    mkdir -p "${destination}"
    echo "Downloading ${name}"
    case "${archive_type}" in
        gz) curl -L --retry 3 --connect-timeout 30 --max-time 300 "${url}" | tar -xz --strip-components=1 -C "${destination}" ;;
        xz) curl -L --retry 3 --connect-timeout 30 --max-time 300 "${url}" | tar -xJ --strip-components=1 -C "${destination}" ;;
        bz2) curl -L --retry 3 --connect-timeout 30 --max-time 300 "${url}" | tar -xj --strip-components=1 -C "${destination}" ;;
        *) echo "error: unsupported archive type ${archive_type}" >&2; exit 1 ;;
    esac
    touch "${destination}/.subtitleedit-ready"
}

mkdir -p "${WORK_DIR}/sources"

download_source "mpv-android-${MPV_ANDROID_COMMIT}" \
    "https://codeload.github.com/mpv-android/mpv-android/tar.gz/${MPV_ANDROID_COMMIT}" gz
download_source "mpv-${MPV_VERSION}" \
    "https://codeload.github.com/mpv-player/mpv/tar.gz/refs/tags/v${MPV_VERSION}" gz
download_source "libass-0.17.4" \
    "https://codeload.github.com/libass/libass/tar.gz/refs/tags/0.17.4" gz
download_source "libplacebo-6.338.2" \
    "https://codeload.github.com/haasn/libplacebo/tar.gz/refs/tags/v6.338.2" gz
download_source "freetype-2.14.3" \
    "https://download.savannah.gnu.org/releases/freetype/freetype-2.14.3.tar.xz" xz
download_source "fribidi-1.0.16" \
    "https://codeload.github.com/fribidi/fribidi/tar.gz/refs/tags/v1.0.16" gz
download_source "harfbuzz-14.2.1" \
    "https://codeload.github.com/harfbuzz/harfbuzz/tar.gz/refs/tags/14.2.1" gz
download_source "libunibreak-7.0" \
    "https://codeload.github.com/adah1972/libunibreak/tar.gz/refs/tags/libunibreak_7_0" gz
download_source "libxml2-2.15.3" \
    "https://gitlab.gnome.org/GNOME/libxml2/-/archive/v2.15.3/libxml2-v2.15.3.tar.gz" gz
download_source "fontconfig-2.16.0" \
    "https://gitlab.freedesktop.org/fontconfig/fontconfig/-/archive/2.16.0/fontconfig-2.16.0.tar.gz" gz

# libplacebo v6 keeps build-time generators and GL bindings in git submodules.
download_source "libplacebo-glad-d08b1aa" \
    "https://codeload.github.com/Dav1dde/glad/tar.gz/d08b1aa01f8fe57498f04d47b5fa8c48725be877" gz
download_source "libplacebo-jinja-b08cd4b" \
    "https://codeload.github.com/pallets/jinja/tar.gz/b08cd4bc64bb980df86ed2876978ae5735572280" gz
download_source "libplacebo-markupsafe-c0254f0" \
    "https://codeload.github.com/pallets/markupsafe/tar.gz/c0254f0cfe51720ecc9e72e8896022af29af5b44" gz
download_source "libplacebo-fast-float-2b2395f" \
    "https://codeload.github.com/fastfloat/fast_float/tar.gz/2b2395f9ac836ffca6404424bcc252bff7aa80e4" gz

BUILD_ROOT="${WORK_DIR}/mpv-android-${MPV_ANDROID_COMMIT}"
if [[ ! -f "${BUILD_ROOT}/.subtitleedit-prepared" ]]; then
    mkdir -p "${BUILD_ROOT}"
    cp -a "${WORK_DIR}/sources/mpv-android-${MPV_ANDROID_COMMIT}/." "${BUILD_ROOT}/"
    touch "${BUILD_ROOT}/.subtitleedit-prepared"
fi

DEPS_DIR="${BUILD_ROOT}/buildscripts/deps"
mkdir -p "${DEPS_DIR}"
link_dependency() {
    local target="$1"
    local source="$2"
    local marker="${DEPS_DIR}/${target}/.subtitleedit-source"
    if [[ -f "${marker}" ]] && [[ "$(<"${marker}")" == "${source}" ]]; then
        return
    fi
    rm -rf "${DEPS_DIR:?}/${target}"
    mkdir -p "${DEPS_DIR}/${target}"
    cp -a "${WORK_DIR}/sources/${source}/." "${DEPS_DIR}/${target}/"
    printf '%s' "${source}" > "${marker}"
}

link_dependency mpv "mpv-${MPV_VERSION}"
link_dependency libass libass-0.17.4
link_dependency libplacebo libplacebo-6.338.2
link_dependency freetype2 freetype-2.14.3
link_dependency fribidi fribidi-1.0.16
link_dependency harfbuzz harfbuzz-14.2.1
link_dependency unibreak libunibreak-7.0
link_dependency libxml2 libxml2-2.15.3
link_dependency fontconfig fontconfig-2.16.0

rm -rf "${DEPS_DIR}/libplacebo/3rdparty/glad" \
    "${DEPS_DIR}/libplacebo/3rdparty/jinja" \
    "${DEPS_DIR}/libplacebo/3rdparty/markupsafe" \
    "${DEPS_DIR}/libplacebo/3rdparty/fast_float"
ln -sfn "${WORK_DIR}/sources/libplacebo-glad-d08b1aa" \
    "${DEPS_DIR}/libplacebo/3rdparty/glad"
ln -sfn "${WORK_DIR}/sources/libplacebo-jinja-b08cd4b" \
    "${DEPS_DIR}/libplacebo/3rdparty/jinja"
ln -sfn "${WORK_DIR}/sources/libplacebo-markupsafe-c0254f0" \
    "${DEPS_DIR}/libplacebo/3rdparty/markupsafe"
ln -sfn "${WORK_DIR}/sources/libplacebo-fast-float-2b2395f" \
    "${DEPS_DIR}/libplacebo/3rdparty/fast_float"

DEPINFO="${BUILD_ROOT}/buildscripts/include/depinfo.sh"
sed -i 's/^dep_mpv=.*/dep_mpv=(libass libplacebo)/' "${DEPINFO}"
sed -i 's/^dep_ffmpeg=.*/dep_ffmpeg=()/' "${DEPINFO}"
sed -i 's/^v_ndk=.*/v_ndk=r27d/' "${DEPINFO}"
sed -i 's/^v_fontconfig=.*/v_fontconfig=2.16.0/' "${DEPINFO}"

BUILD_ALL="${BUILD_ROOT}/buildscripts/buildall.sh"
sed -i 's/local apilvl=23/local apilvl=24/' "${BUILD_ALL}"

MPV_SCRIPT="${BUILD_ROOT}/buildscripts/scripts/mpv.sh"
sed -i \
    -e 's/-D{lua,libcurl}=enabled/-Dlua=disabled -Djavascript=disabled -Dcplugins=disabled -Dlibarchive=disabled/' \
    -e 's/-D{lua,libcurl}=disabled/-Dlua=disabled -Djavascript=disabled -Dcplugins=disabled -Dlibarchive=disabled/' \
    "${MPV_SCRIPT}"

cp "${SCRIPT_DIR}/meson-setup-wrapper.sh" \
    "${BUILD_ROOT}/buildscripts/include/subtitleedit-meson.sh"

# WSL can briefly report newly-created files as being less than a second in
# the future. Meson treats that as fatal, so let the host clock catch up before
# Ninja reads the generated build state.
for meson_script in freetype2 fribidi harfbuzz libxml2 fontconfig libplacebo mpv; do
    script_path="${BUILD_ROOT}/buildscripts/scripts/${meson_script}.sh"
    if ! grep -q 'subtitleedit-meson.sh' "${script_path}"; then
        sed -i '/^\. \.\.\/\.\.\/include\/path\.sh/a . ../../include/subtitleedit-meson.sh' \
            "${script_path}"
        sed -i 's/^meson setup /subtitleedit_meson_setup /' "${script_path}"
    fi
    if ! grep -q 'subtitleedit-clock-sync' "${script_path}"; then
        sed -i '/^[[:space:]]*ninja -C \$build -j\$cores/i sleep 2 # subtitleedit-clock-sync' \
            "${script_path}"
    fi
done

UNIBREAK_SCRIPT="${BUILD_ROOT}/buildscripts/scripts/unibreak.sh"
sed -i '/mkdir -p \$build/i [ -f configure ] || autoreconf -fi' "${UNIBREAK_SCRIPT}"

mkdir -p "${BUILD_ROOT}/buildscripts/sdk"
ln -sfn "${ANDROID_NDK_ROOT}" "${BUILD_ROOT}/buildscripts/sdk/android-ndk-r27d"

stage_ffmpeg_prefix() {
    local mpv_arch="$1"
    local ffmpeg_arch="$2"
    local prefix="${BUILD_ROOT}/buildscripts/prefix/${mpv_arch}"
    local ffmpeg_prefix="${FFMPEG_KIT_DIR}/prebuilt/android-${ffmpeg_arch}-24/ffmpeg"
    local pc_layout_version="2"
    local pc_layout_marker="${prefix}/.subtitleedit-ffmpeg-pc-layout"
    if [[ ! -f "${ffmpeg_prefix}/lib/libavcodec.so" ]]; then
        echo "error: FFmpegKit prefix missing for ${ffmpeg_arch}: ${ffmpeg_prefix}" >&2
        exit 1
    fi
    mkdir -p "${prefix}/include" "${prefix}/lib/pkgconfig"
    ln -sfn . "${prefix}/usr"
    ln -sfn . "${prefix}/local"
    cp -a "${ffmpeg_prefix}/include/." "${prefix}/include/"
    cp -a "${ffmpeg_prefix}/lib/"*.so "${prefix}/lib/"
    cp -a "${ffmpeg_prefix}/lib/pkgconfig/." "${prefix}/lib/pkgconfig/"
    # buildall.sh sets PKG_CONFIG_SYSROOT_DIR to this prefix. Keep FFmpeg's
    # pkg-config paths relative to that sysroot, otherwise an absolute path
    # from the FFmpegKit build gets prefixed a second time by pkg-config.
    sed -i \
        -e 's|^prefix=.*|prefix=/|' \
        -e 's|^exec_prefix=.*|exec_prefix=/|' \
        -e 's|^libdir=.*|libdir=/lib|' \
        -e 's|^includedir=.*|includedir=/include|' \
        "${prefix}/lib/pkgconfig/"*.pc
    if [[ ! -f "${pc_layout_marker}" ]] || \
        [[ "$(<"${pc_layout_marker}")" != "${pc_layout_version}" ]]; then
        rm -rf "${DEPS_DIR}/mpv/_build_${mpv_arch}"
        printf '%s' "${pc_layout_version}" > "${pc_layout_marker}"
    fi
}

stage_ffmpeg_prefix armv7l arm
stage_ffmpeg_prefix arm64 arm64
stage_ffmpeg_prefix x86 x86
stage_ffmpeg_prefix x86_64 x86_64

cd "${BUILD_ROOT}/buildscripts"
for arch in armv7l arm64 x86 x86_64; do
    if [[ -f "${BUILD_ROOT}/buildscripts/prefix/${arch}/lib/libmpv.so" ]]; then
        echo "Reusing libmpv for ${arch}"
    else
        ./buildall.sh --arch "${arch}" mpv
    fi
done

JNI_BUILD="${WORK_DIR}/jni-build"
mkdir -p "${JNI_BUILD}"
cp -a "${SCRIPT_DIR}/jni/." "${JNI_BUILD}/"

PREFIX_ARMV7="${BUILD_ROOT}/buildscripts/prefix/armv7l" \
PREFIX_ARM64="${BUILD_ROOT}/buildscripts/prefix/arm64" \
PREFIX_X86="${BUILD_ROOT}/buildscripts/prefix/x86" \
PREFIX_X86_64="${BUILD_ROOT}/buildscripts/prefix/x86_64" \
"${ANDROID_NDK_ROOT}/ndk-build" \
    NDK_PROJECT_PATH="${JNI_BUILD}" \
    APP_BUILD_SCRIPT="${JNI_BUILD}/Android.mk" \
    NDK_APPLICATION_MK="${JNI_BUILD}/Application.mk" \
    NDK_OUT="${JNI_BUILD}/obj" \
    NDK_LIBS_OUT="${JNI_BUILD}/libs" \
    -j"$(nproc)"

MAVEN_ROOT="${PROJECT_ROOT}/app/libs/mpv-runtime-maven"
RUNTIME_DIR="${MAVEN_ROOT}/com/subtitleedit/native/mpv-android-runtime/${RUNTIME_VERSION}"
FFMPEG_DIR="${PROJECT_ROOT}/app/libs/ffmpeg-kit-next-maven/com/arthenica/ffmpeg-kit-next/${FFMPEG_ARTIFACT_VERSION}"
PACKAGE_ROOT="${WORK_DIR}/aar-root"
rm -rf "${PACKAGE_ROOT}"
mkdir -p "${PACKAGE_ROOT}/jni" "${PACKAGE_ROOT}/META-INF/com/android/build/gradle" "${PACKAGE_ROOT}/empty"

cat >"${PACKAGE_ROOT}/AndroidManifest.xml" <<'MANIFEST'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.subtitleedit.mpv.runtime" />
MANIFEST

cat >"${PACKAGE_ROOT}/META-INF/com/android/build/gradle/aar-metadata.properties" <<'METADATA'
aarFormatVersion=1.0
aarMetadataVersion=1.0
minCompileSdk=24
minCompileSdkExtension=0
METADATA

jar cf "${PACKAGE_ROOT}/classes.jar" -C "${PACKAGE_ROOT}/empty" .
rm -rf "${PACKAGE_ROOT}/empty"

for abi in armeabi-v7a arm64-v8a x86 x86_64; do
    mkdir -p "${PACKAGE_ROOT}/jni/${abi}"
    cp "${JNI_BUILD}/libs/${abi}/libsubtitleedit_mpv.so" "${PACKAGE_ROOT}/jni/${abi}/"
    cp "${JNI_BUILD}/libs/${abi}/libmpv.so" "${PACKAGE_ROOT}/jni/${abi}/"
    cp "${JNI_BUILD}/libs/${abi}/libc++_shared.so" "${PACKAGE_ROOT}/jni/${abi}/"
done

mkdir -p "${RUNTIME_DIR}"
(cd "${PACKAGE_ROOT}" && zip -qr \
    "${RUNTIME_DIR}/mpv-android-runtime-${RUNTIME_VERSION}.aar" .)

cat >"${RUNTIME_DIR}/mpv-android-runtime-${RUNTIME_VERSION}.pom" <<POM
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.subtitleedit.native</groupId>
  <artifactId>mpv-android-runtime</artifactId>
  <version>${RUNTIME_VERSION}</version>
  <packaging>aar</packaging>
  <dependencies>
    <dependency>
      <groupId>com.arthenica</groupId>
      <artifactId>ffmpeg-kit-next</artifactId>
      <version>${FFMPEG_ARTIFACT_VERSION}</version>
      <scope>runtime</scope>
    </dependency>
  </dependencies>
</project>
POM

BASE_FFMPEG_AAR="${PROJECT_ROOT}/app/libs/ffmpeg-kit-next-maven/com/arthenica/ffmpeg-kit-next/8.1.0/ffmpeg-kit-next-8.1.0.aar"
if [[ ! -f "${BASE_FFMPEG_AAR}" ]]; then
    echo "error: base FFmpegKit AAR not found: ${BASE_FFMPEG_AAR}" >&2
    exit 1
fi
mkdir -p "${FFMPEG_DIR}"
FFMPEG_AAR_ROOT="${WORK_DIR}/ffmpeg-aar-root"
rm -rf "${FFMPEG_AAR_ROOT}"
mkdir -p "${FFMPEG_AAR_ROOT}"
unzip -q "${BASE_FFMPEG_AAR}" -d "${FFMPEG_AAR_ROOT}"
rm -rf "${FFMPEG_AAR_ROOT}/jni"
for abi in armeabi-v7a arm64-v8a x86 x86_64; do
    mkdir -p "${FFMPEG_AAR_ROOT}/jni/${abi}"
    cp "${FFMPEG_KIT_DIR}/android/libs/${abi}/"*.so "${FFMPEG_AAR_ROOT}/jni/${abi}/"
done
(cd "${FFMPEG_AAR_ROOT}" && zip -qr \
    "${FFMPEG_DIR}/ffmpeg-kit-next-${FFMPEG_ARTIFACT_VERSION}.aar" .)
cat >"${FFMPEG_DIR}/ffmpeg-kit-next-${FFMPEG_ARTIFACT_VERSION}.pom" <<POM
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.arthenica</groupId>
  <artifactId>ffmpeg-kit-next</artifactId>
  <version>${FFMPEG_ARTIFACT_VERSION}</version>
  <packaging>aar</packaging>
  <dependencies>
    <dependency>
      <groupId>com.arthenica</groupId>
      <artifactId>smart-exception-java</artifactId>
      <version>0.2.1</version>
      <scope>runtime</scope>
    </dependency>
  </dependencies>
</project>
POM

echo "Published FFmpegKit ${FFMPEG_ARTIFACT_VERSION} and mpv runtime ${RUNTIME_VERSION}"
