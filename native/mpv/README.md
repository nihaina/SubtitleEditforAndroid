# mpv Android runtime

The media-processing subtitle page also uses this runtime to read source
subtitle tracks and embedded lyric metadata. The app builds `subtitleedit_mpv_probe` from
`app/src/main/cpp/mpv_subtitle_probe.cpp`; it opens a separate paused, headless
libmpv handle so probing cannot replace the editor's playback session. It maps
`track-list` entries using `ff-index`, not mpv's per-type track IDs. Automatic
external-file loading is disabled, matching the editor's playback options.
Lyric tags come from the `metadata` node map and are returned separately from
stream indices. MP3/WAV USLT and SYLT frames are also read directly without
modifying the source, since the demuxer does not expose SYLT as metadata.
The client header is pinned to mpv v0.41.0 at
`app/src/main/cpp/third_party/mpv/client.h` and retains its ISC notice.

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

The generated mpv AAR intentionally excludes all `libav*.so` files. The original
runtime used the shared libraries from `ffmpeg-kit-next:8.1.0-mpv1`.

Media conversion now uses `ffmpeg-kit-next:8.1.0-mpv5`, built from the same
FFmpeg version with additional software encoders and subtitle rendering. See `../ffmpeg/README.md`.
Its packager checks that all old exported symbols remain available before
publishing, so the existing mpv runtime can keep using those shared libraries.
The commands below describe rebuilding the original mpv/FFmpeg baseline;
run `../ffmpeg/rebuild-codecs.sh` afterwards to produce the current codec AAR.

The patched runtime is published locally as `0.41.0-ffmpeg8.1.2-2`.

The runtime build applies `patches/mpv-0.41.0-audiotrack-pause.patch`. This
adds a real pause callback to mpv's Android `audiotrack` output: user pause and
resume call `AudioTrack.pause()`/`play()` without flushing queued PCM. The
existing reset path still flushes during seek and output reconfiguration.
Interrupted writes retain their unwritten PCM tail and retry it before reading
more samples. Resume rejects AudioTrack timestamps captured before playback
restarted, so paused time cannot be extrapolated into the playback position.

The patch is applied automatically by `build-runtime.sh`. The script invalidates
old per-ABI mpv outputs when the patch contents change, so an existing work
directory cannot silently reuse an unpatched `libmpv.so`.

Build both standard APK variants, then verify the AARs and APKs with `readelf`:

```bash
./gradlew :app:assembleStandardDebug :app:assembleStandardRelease
./native/mpv/verify-runtime.sh
```
