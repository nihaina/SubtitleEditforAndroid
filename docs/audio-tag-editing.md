# Audio tag editing

Audio tag editing follows the native TagLib read/write approach used by
[Lyrico](https://github.com/Replica0110/Lyrico). The JNI bridge is implemented
independently; upstream source, revisions and licenses are recorded in
`app/src/main/cpp/third_party/taglib/UPSTREAM.md` and `THIRD_PARTY_NOTICES.md`.
The vendored source builds offline for all app ABIs.

## Read and save

`MediaTagAudioMetadata` maps the thirteen editable audio fields to TagLib
property names. Only fields marked as edited are written; an empty edited field
deletes its tags. Native readback and the final publication check use the same
comparison rules for ID3 numeric genres and container-normalized track/disc
numbers. Batch edits retain each file's untouched values.

`MediaTagAudioRepository` reads audio tags and pictures through borrowed file
descriptors. Native code duplicates descriptors before handing ownership to
TagLib. WAV reads combine missing ID3 and RIFF INFO fields; saves synchronize
edited values in both tag families. The properties dialog uses the same reader.

`MediaTagEditorViewModel` copies the source into private staging, changes and
validates that copy, copies finalized bytes to a sibling staging file, verifies
the source snapshot again and atomically renames the sibling into place. Saving
never re-encodes audio. The published file is reopened before the next save and
the media scanner is notified. Android media database columns are not used as
the source of tag values.

## Preservation

Unedited ID3 frames remain native frame objects, including multiple USLT/SYLT
lyrics, language/description variants, private data and artwork. ASF attributes
retain their native data types, language and stream indexes; MP4 items retain
binary freeforms and atom types. These objects must not be reconstructed from
TagLib's string property map merely to edit an unrelated text field.

Default artwork preserves images. Replacement changes the front cover while
retaining typed back/other pictures; MP4 artwork has no picture types. Delete
removes all artwork. Export copies image bytes, with MIME or signature-based
extensions for APEv2 pictures.

## Fallbacks and tests

ADTS AAC is routed explicitly by FFprobe's detected container. TagLib's MPEG
content detector can otherwise accept AAC payload bytes. The existing ID3
parser/writer changes its leading tag on a private copy while retaining the
complete AAC payload and unedited frames; unsupported tag structures fail
before publication. Video and other unsupported audio retain the existing
stream-copy command path. WAV/RF64 fallback restores original ID3 metadata.

JVM tests cover field mapping, command generation, ID3/RF64 preservation and
validation. `MediaTagNativeWorkflowTest`, `MediaTagAacWorkflowTest` and
`MediaTagEditorWorkflowTest` exercise native/container writes and the actual
editor workflow on Android. Small independent WMA/MP4 preservation fixtures are
documented under `app/src/androidTest/assets/media-tags`. The optional
`taglibSampleRoot` instrumentation argument accepts a directory containing
`user.wav` and `user.mp3`; tests copy them before writing and leave the results
under the app's external files directory for independent tag-reader inspection.
