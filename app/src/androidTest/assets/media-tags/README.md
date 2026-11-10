These short sine-wave audio fixtures were generated for the tag editor tests.
They contain no third-party recordings.

- `preservation.wma`: 0.3-second WMAv2, tagged with Mutagen 1.48.1.
  `WM/EncodingTime` is a QWord with value `133700000000000000`.
  `WM/Lyrics` is Unicode text `[00:00.00]Language indexed lyric`, language index 1,
  stream index 1. Editing a title must preserve the native types and indexes.
- `preservation.m4a`: 0.3-second AAC in MP4, tagged with Mutagen 1.48.1.
  `----:com.apple.iTunes:TEST_BINARY` is an implicit-type freeform atom with bytes
  `00 ff 91 00 10 42 69 6e 61 72 79 00`. A text tag edit must retain its data/type.
