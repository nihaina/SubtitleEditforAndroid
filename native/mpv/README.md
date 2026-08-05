# mpv Android runtime

The runtime is built against the exact FFmpeg shared libraries produced by
FFmpegKit Next. Run the build from the FFmpegKit Android Nix shell:

```bash
cd /home/nihaina/src/subtitleedit-ffmpeg-kit-next
nix develop .#android-r27d -c \
  /mnt/d/Work/VSCode/SubtitleEditforAndroid/native/mpv/build-runtime.sh
```

Pinned inputs:

- FFmpegKit Next 8.1.0 / FFmpeg n8.1.2
- mpv v0.41.0
- mpv-android wrapper commit `20a3fa526fac6d3fe267aee0d4c349893fee65a3`
- libass 0.17.4
- libplacebo v6.338.2

The generated mpv AAR intentionally excludes all `libav*.so` files. They are
provided only by `ffmpeg-kit-next:8.1.0-mpv1`.

Verify the four ABI AARs with `readelf` after building:

```bash
./native/mpv/verify-runtime.sh
```
