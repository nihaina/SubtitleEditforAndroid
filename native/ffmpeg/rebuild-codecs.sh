#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ffmpeg_kit_dir="${FFMPEG_KIT_DIR:-${HOME}/src/subtitleedit-ffmpeg-kit-next}"
cd "${ffmpeg_kit_dir}"
cp "${script_dir}/gsm.sh" scripts/android/gsm.sh

./nix-android.sh -p android-r27d \
  --disable-arm-v7a-neon --enable-gpl \
  --enable-lame --enable-libiconv --enable-libvorbis --enable-libvpx \
  --enable-x264 --enable-opus --enable-android-zlib --enable-android-media-codec \
  --enable-x265 --enable-xvidcore --enable-libaom --enable-libtheora \
  --enable-speex --enable-opencore-amr --enable-vo-amrwbenc \
  --enable-custom-library-1-name=gsm \
  --enable-custom-library-1-repo=https://github.com/timothytylee/libgsm.git \
  --enable-custom-library-1-repo-commit=98f1708fb5e06a0dfebd58a3b40d610823db9715 \
  --enable-custom-library-1-package-config-file-name=gsm \
  --enable-custom-library-1-ffmpeg-enable-flag=libgsm \
  --enable-custom-library-1-license-file=COPYRIGHT \
  --rebuild-ffmpeg --no-archive "$@"

python3 "${script_dir}/package-codecs.py" "${ffmpeg_kit_dir}"
