# Media conversion codecs

`rebuild-codecs.sh` builds FFmpeg n8.1.2 using FFmpegKit Next 8.1.0 and the
existing `android-r27d` Nix environment. It preserves the codecs, zlib,
MediaCodec and SAF support used by the mpv runtime, and adds:

| Library | Source tag | Output encoder |
| --- | --- | --- |
| x265 | `arthenica/x265`, `4.2` | `libx265` |
| Xvid | `arthenica/xvidcore`, `release-1_3_7` | `libxvid` |
| libaom | `arthenica/libaom`, `v3.12.1` | `libaom-av1` |
| Theora | `arthenica/theora`, `v1.1.1` | `libtheora` |
| Speex | `arthenica/speex`, `Speex-1.2.1` | `libspeex` |
| OpenCORE AMR | `arthenica/opencore-amr`, `v0.1.6` | `libopencore_amrnb` |
| VisualOn AMR-WB | `arthenica/vo-amrwbenc`, `v0.1.3` | `libvo_amrwbenc` |
| GSM 06.10 | `timothytylee/libgsm`, `98f1708fb5e06a0dfebd58a3b40d610823db9715` (1.0.16) | `libgsm`, `libgsm_ms` |

FFmpegKit checkout revision: `3e223118e6e8fb6208693ecf3952e77cd096f587`.
The upstream x265 recipe builds the 10-bit encoder. FFmpeg negotiates its
supported pixel format when an 8-bit source is supplied. Software HEVC and
AV1 encoding performance depends on resolution and device CPU.

From WSL/Linux with Nix available:

```bash
export FFMPEG_KIT_DIR="$HOME/src/subtitleedit-ffmpeg-kit-next"
bash native/ffmpeg/rebuild-codecs.sh --jobs=12
```

To package an already completed build:

```bash
python3 native/ffmpeg/package-codecs.py "$FFMPEG_KIT_DIR"
```

The script publishes `ffmpeg-kit-next:8.1.0-mpv4`, keeping previous artifacts
available for recovery. The packager compares exported symbols
against the previous `8.1.0-mpv3` artifact. Before publishing, every ABI must contain all added
libraries, preserve every old exported symbol, and pass 16 KB ELF alignment
checks for 64-bit libraries. The existing mpv runtime uses the same FFmpeg
version and SONAMEs; it does not ship a second FFmpeg copy.

The AAR includes the additional upstream licenses and libaom patent notice
as Android raw resources. The combined application remains GPLv3.

Previous `8.1.0-mpv2` AAR SHA-256:
`c7243d77d75c5c41d0abe439adf8eee9e1baf9a74fd0dc7c677af070a6f322f8`.

`MediaConvertCommandTest` checks command rules. For host conversion checks,
set `MEDIA_CONVERT_SMOKE_FFMPEG` to a host FFmpeg executable and run
`MediaConvertFfmpegSmokeTest`; it exercises every offered container/video
encoder combination with generated input. Host checks do not substitute for
Android execution or device performance testing.

`MediaConvertAudioFfmpegTest` encodes each offered audio codec in each video
container, checks the codec ID with FFprobe, then decodes the audio back to PCM.
`MediaConvertAudioCodecsTest` covers codec discovery, per-container choices,
experimental flags and rate/channel/bitrate adaptation. Built-in FFmpeg encoders
provide DTS, TTA, WavPack, PCM, ADPCM, WMA and RealAudio; the three added libraries
supply Speex and AMR-NB/WB encoding. The pinned GSM source is built with the
`gsm.sh` custom-library recipe, including WAV49 support and position-independent code.

Audio-only output uses the same codec profiles and bitrate/sample-rate/channel
dialogs as video audio tracks. `MediaConvertAudioFormats` defines the extension,
muxer and encoder combinations. `MediaConvertAudioOutputFfmpegTest` covers each
offered combination with stereo defaults and 96 kHz surround input plus requested
settings, and checks codec identification and decoding. GSM formats appear only
when runtime encoder discovery confirms availability. Android runtime tests are
in `MediaConvertNativeTest`; compiling them does not execute them on a device.

Previous `8.1.0-mpv3` AAR SHA-256:
`a794376104fccd13279d64044b81c4423303e39159ae384949f09f43d3175fb2`.

Current `8.1.0-mpv4` AAR SHA-256:
`3a1c19af4ddbb000c66240f108e4cc46f4b018930a9cae93b9301eae2fb7f5c9`.
