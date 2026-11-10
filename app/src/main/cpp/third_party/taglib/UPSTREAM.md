# TagLib source snapshot

- Upstream: https://github.com/taglib/taglib
- Commit: `a27dca557e85db1e23de33d2118cde87c8f32770`
- Version reported by this snapshot: 2.3.0
- License: LGPL-2.1 or MPL-1.1, see `COPYING.LGPL` and `COPYING.MPL`.
- UTF conversion dependency: https://github.com/nemtrif/utfcpp
- utfcpp commit: `63d64de49fd6b829f7c8694df5ab2ee625cb7134`
- utfcpp license: Boost Software License 1.0, see `3rdparty/utfcpp/LICENSE`.

These are unmodified upstream source snapshots (without Git metadata). The
`UPSTREAM.md` file is added by SubtitleEdit for Android. Builds use the included
utfcpp headers and do not fetch dependencies from the network.

The integration follows the tag-aware read/write architecture used by
[Lyrico](https://github.com/Replica0110/Lyrico). SubtitleEdit's JNI bridge is
implemented independently in `../../media_tags_jni.cpp`; no Lyrico application
source is included. The parent CMake file builds TagLib statically into
`libsubtitleedit_tags.so`, with other native targets retaining their existing
shared-library configuration.

The bridge borrows Android descriptors by duplicating them before TagLib takes
ownership. It applies edited fields to the file's native tags, updates existing
secondary tag families, and preserves untouched ID3 frames outside the property
roundtrip. WAV edits update both ID3v2 and RIFF INFO. The Kotlin caller writes a
staging copy and reads its saved tags back before replacing the source.
