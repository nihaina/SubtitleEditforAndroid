#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ffmpeg_kit_dir="${FFMPEG_KIT_DIR:-${HOME}/src/subtitleedit-ffmpeg-kit-next}"
cd "${ffmpeg_kit_dir}"

./nix-android.sh -p android-r27d \
  --disable-arm-v7a-neon --enable-gpl \
  --enable-lame --enable-libiconv --enable-libvorbis --enable-libvpx \
  --enable-x264 --enable-opus --enable-android-zlib --enable-android-media-codec \
  --enable-x265 --enable-xvidcore --enable-libaom --enable-libtheora \
  --rebuild-ffmpeg --no-archive "$@"

python3 "${script_dir}/package-codecs.py" "${ffmpeg_kit_dir}"
