# mpv Android runtime

The runtime is built against the exact FFmpeg shared libraries produced by
FFmpegKit Next. From this repository's root, set the FFmpegKit checkout path and
run the build in its Android Nix shell:

```bash
export FFMPEG_KIT_DIR="${HOME}/src/subtitleedit-ffmpeg-kit-next"
subtitleedit_root="$(pwd)"
cd "${FFMPEG_KIT_DIR}"
nix develop .#android-r27d -c \
  "${subtitleedit_root}/native/mpv/build-runtime.sh"
```

Set `FFMPEG_KIT_DIR` to your checkout if it is elsewhere. The script uses
`${HOME}/src/subtitleedit-ffmpeg-kit-next` by default.

Pinned inputs:

- FFmpegKit Next 8.1.0 / FFmpeg n8.1.2
- mpv v0.41.0
- mpv-android wrapper commit `20a3fa526fac6d3fe267aee0d4c349893fee65a3`
- libass 0.17.4
- libplacebo v6.338.2

The FFmpegKit prefix used by this runtime is built with the media-conversion
codecs `libmp3lame`, `libx264`, `libvpx`, `libvorbis` and `libopus` enabled,
plus Android zlib and MediaCodec support. Rebuild that prefix with the same
options before regenerating this runtime.

The generated mpv AAR intentionally excludes all `libav*.so` files. They are
provided only by `ffmpeg-kit-next:8.1.0-mpv1`.

Verify the four ABI AARs with `readelf` after building:

```bash
./native/mpv/verify-runtime.sh
```
