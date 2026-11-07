# Third-Party Notices

SubtitleEdit for Android includes the following third-party components.
Their licenses apply to those components independently of the project's
GPL-3.0 license.

## chardet4j 78.1.0

Automatic subtitle charset detection uses [chardet4j](https://github.com/sigpwned/chardet4j),
copyright 2022 Andy Boothe, under the Apache License 2.0. The dependency is
resolved from Maven Central (`com.sigpwned:chardet4j:78.1.0`).

## llama.cpp and ggml

The local LLM runtime includes source from [llama.cpp](https://github.com/ggml-org/llama.cpp),
including its in-tree [ggml](https://github.com/ggml-org/ggml) library. The
llama.cpp and ggml sources are Copyright (c) 2023-2026 The ggml authors and
are distributed under the MIT License.

The vendored copy is based on the
[llama.cpp-android](https://github.com/andriydruk/llama.cpp-android)
`android-b11200` branch at commit `f825a61c7f0c99402fc06c1c0113ffcb6da1c3a4`.
It contains Android-specific changes, including SAF file-descriptor loading
(`fd:N`) and Android backend integration. The source is included in
`app/src/main/cpp/llama.cpp`; the complete upstream MIT license and copyright
notice are retained in `app/src/main/cpp/llama.cpp/LICENSE`.

The llama.cpp tree also contains separately licensed vendored dependencies.
Their license files remain in that tree and apply to the corresponding files,
including `app/src/main/cpp/llama.cpp/licenses/` and the individual dependency
directories under `app/src/main/cpp/llama.cpp/vendor/`.

## Nagisa 0.3.0

The Japanese word-segmentation model and vocabulary are from Nagisa 0.3.0,
copyright 2018 taishi-i, under the MIT License. The model's inference path is
ported to Rust; the original Python and DyNet runtimes are not included. The
license is distributed in `native/qwen-tokenizer/assets/NAGISA_LICENSE.txt`
and in the APK at `assets/licenses/NAGISA_LICENSE.txt`.

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
FFmpegKit Next. The local build enables libmp3lame, libx264, libvpx, libvorbis,
libopus, x265 4.2, Xvid 1.3.7, libaom 3.12.1, Theora 1.1.1, Speex 1.2.1,
OpenCORE AMR 0.1.6, VisualOn AMR-WB 0.1.3 and GSM 1.0.16. x264, x265
and Xvid are GPL-licensed, so this FFmpegKit build and the combined
application are distributed under GPLv3. The reproducible build options are
documented in `app/libs/README.md`, `native/mpv/README.md` and
`native/ffmpeg/README.md`. The additional upstream license texts and libaom
patent notice are included as `res/raw/license_x265.txt`,
`res/raw/license_xvidcore.txt`, `res/raw/license_libaom.txt`,
`res/raw/patents_libaom.txt` and `res/raw/license_libtheora.txt` in the AAR.
libaom is distributed under its BSD 2-Clause license and patent grant;
GSM uses the permissive TU Berlin license (bundled as `license_gsm`).
Theora and Speex use BSD-style licenses. OpenCORE AMR and VisualOn AMR-WB
use Apache License 2.0. Their complete notices are included in the AAR as
`res/raw/license_speex.txt`, `res/raw/license_opencore_amr.txt` and
`res/raw/license_vo_amrwbenc.txt`.

FFmpeg subtitle burn-in additionally links libass 0.17.4 (ISC), FreeType 2.14.3
(FreeType License), FriBidi 1.0.16 (LGPL), HarfBuzz 14.2.1 (MIT), Fontconfig
2.18.1 (MIT-style), Expat 2.8.1 (MIT) and libpng 1.6.58 (libpng license).
Their complete notices are bundled in the AAR as `res/raw/license_libass.txt`,
`res/raw/license_freetype.txt`, `res/raw/license_fribidi.txt`,
`res/raw/license_harfbuzz.txt`, `res/raw/license_fontconfig.txt`,
`res/raw/license_expat.txt` and `res/raw/license_libpng.txt`.
Fontconfig also uses libuuid 1.0.3 (BSD-style); its license is bundled as
`res/raw/license_libuuid.txt`.

## libass and libplacebo

libass 0.17.4 provides subtitle rendering and libplacebo 6.338.2 provides the
GPU rendering pipeline used by libmpv. Their upstream license notices and
source revisions are retained by the reproducible native build described in
`native/mpv/README.md`.

## Qualcomm QNN Runtime 2.40.0.251030

The optional arm64 QNN build includes Qualcomm QNN HTP runtime libraries
distributed by the sherpa-onnx project in its `asr-models-qnn` release. These
libraries enable NPU execution on compatible Snapdragon devices and remain
subject to the Qualcomm software terms applicable to the QNN/QAIRT runtime.
The standard build omits these libraries.
