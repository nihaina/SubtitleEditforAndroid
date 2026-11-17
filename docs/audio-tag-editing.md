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

## Reading outside the editor

Embedded pictures now go through `SoftwareMediaCoverReader`, shared by the
browser, library, player and notifications. TagLib reads ordinary tag families
including MP4 and ASF video covers; bounded Matroska, Ogg and ID3 readers cover
picture attachments, multiplexed Vorbis comments and RF64/BW64 ID3 chunks.
Android's `embeddedPicture` is not used. Text tags use TagLib, with a cached
FFprobe fallback for unsupported containers when library indexing or explicit
tag inspection supplies a private probe directory. Matroska text tags prefer
FFprobe because TagLib omits the Segment Info title and some FFmpeg tag aliases.
A supported empty native tag
map remains authoritative, so cleared fields are not refilled by another reader.

Files outside the scanned library, favorites and user playlists are not parsed
for full text tags during normal browsing or playback. Rows, history and
notifications use file names. Opening an information/properties view or the tag
editor explicitly reads complete tags temporarily, without adding the file to
the catalog. Embedded covers are
read and displayed independently of text tags, including in ordinary file
previews, player artwork, information views and notifications. Technical duration
and video frame thumbnails also remain available.
Scanning a directory or adding a
track to a collection opts it into library metadata. Playback and returning from
the tag editor never create a catalog entry. Editing an existing library or
collection member refreshes its stored tags. Adding or removing collection
membership updates the current notification's text; the embedded cover remains
available in either case.

Technical duration, stream properties and video frame thumbnails still use the
platform APIs. The software change concerns embedded artwork and text tags,
not playback decoding. Cover-only requests do not request complete text tags;
reading tags in an information view or the editor does not enable their automatic
display during ordinary browsing or playback.

Common-directory shortcuts and library roots remain separate sources. Adding a
common shortcut only enables ordinary file browsing; it never enrolls the
directory in recursive media indexing or category catalogs. A library root is
also visible in the shortcuts page. Adding an existing common shortcut through
the library picker promotes the same path to a library root without duplication
or downgrade. Only explicit library roots feed the file catalog and recursive
observer; files under a retained nested library root stay indexed when a broader
shortcut/root is removed. Startup and configuration changes prune previously
misindexed rows and album metadata outside the configured library roots, while
preserving explicit collections and playback/listening records. Information
views are temporary reads; category links only locate existing catalog members.
