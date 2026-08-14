# Third-Party Notices

SubtitleEdit for Android includes the following archive-related components.
Their licenses apply to those components independently of the project's
GPL-3.0 license.

## 7-Zip 26.02

Copyright (C) 1999-2026 Igor Pavlov.

Most 7-Zip source files are licensed under LGPL-2.1-or-later. The RAR decoder
files additionally carry the unRAR restriction. LZFSE and Zstandard decoder
files use the BSD 3-Clause License, and XXH64 uses the BSD 2-Clause License.
The complete upstream notice and all applicable license terms are distributed
in `app/src/main/cpp/third_party/7zip/7zip-LICENSE.txt` and in the APK at
`assets/licenses/7zip-LICENSE.txt`.

The unRAR-derived sources must not be used to develop a RAR-compatible
archiver. This application only exposes RAR extraction.

## mpv 0.41.0

mpv is copyright its contributors and is distributed under
GPL-2.0-or-later. This application combines libmpv with GPLv3 application
code and distributes the resulting work under GPLv3. The corresponding build
configuration and pinned source revisions are documented in `native/mpv/`.

## mpv-android JNI wrapper

The minimal Android Surface and JNI integration is derived from mpv-android,
commit `20a3fa526fac6d3fe267aee0d4c349893fee65a3`, under the MIT License. The
license text is distributed at `assets/licenses/mpv-android-LICENSE.txt`.

## FFmpegKit Next and FFmpeg

FFmpegKit Next 8.1.0 is used to build FFmpeg n8.1.2 and the Java/JNI command
API. libmpv is linked against the same FFmpeg shared libraries packaged by
FFmpegKit Next. The enabled FFmpeg configuration is LGPL-compatible; libmpv
and the combined application remain governed by GPLv3 as described above.

## libass and libplacebo

libass 0.17.4 provides subtitle rendering and libplacebo 6.338.2 provides the
GPU rendering pipeline used by libmpv. Their upstream license notices and
source revisions are retained by the reproducible native build described in
`native/mpv/README.md`.

## Qualcomm QNN Runtime 2.40.0.251030

The arm64 build includes Qualcomm QNN HTP runtime libraries distributed by the
sherpa-onnx project in its `asr-models-qnn` release. These libraries enable NPU
execution on compatible Snapdragon devices and remain subject to the Qualcomm
software terms applicable to the QNN/QAIRT runtime.
