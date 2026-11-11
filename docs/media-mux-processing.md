# Audio/video processing pages

`MediaConvertActivity` hosts `MediaProcessingScreen`: format conversion remains
the first page, and mux processing is the second. Each page owns its existing
or new ViewModel independently. The launch `EXTRA_PAGE` chooses the initial page;
inputs are consumed once per retained ViewModel. Running work disables page
switching. The directory shortcut first displays a page chooser. Subtitle
selections keep conversion available and disable mux processing; a selection
unsupported by both pages hides the shortcut.

## Shared queue

`MediaMuxQueue` models explicit rows with video, audio and output columns.
Imports fill the first vacant matching column slot in top-to-bottom order.
Each imported occurrence receives its own UUID, including duplicate paths.
Removing an item leaves a hole. Moving a video column slot also moves its
result; moving audio slots changes only pairing. The order dialog supports
dragging, up/down actions, name sorting and reversal, including empty slots.
Removing an output clears only that row's displayed result. It leaves the
generated file, input pairing and video-owned output folder intact.

Merge processes rows containing both inputs. Split processes rows containing
only a video input. Other rows are dimmed without removing their contents or
disabling idle editing. Splitting fills the audio cell with the extracted
file and the output cell with the video-only file; the left source remains.

`MediaMuxCommand` builds stream-copy commands, with no hidden encoding or
container change. Source audio detection prompts replace/add, optionally for
all remaining rows in that run. The decision is reset for the next run.
Unsupported source/container combinations fail with an error. Audio extraction
uses a container compatible with its codec; multiple audio tracks use MKA.

## Output ownership

For output root `R` and video `test.mp4`, successful files are published under
`R/test.mp4/合并/` or `R/test.mp4/分离/`. The video keeps its original full
filename. Extracted audio keeps the video stem and receives the matching audio
extension. Only operation directories with successful outputs are retained.

`MediaMuxOutputStore` binds the output root plus video UUID to an outer folder.
Reordering, changing the paired audio, switching mode or processing again reuses
that folder while the video entry remains queued. Existing output files owned
by that entry are replaced transactionally. Unrelated files are not overwritten.
Duplicate names and reimported entries get `test.mp4（1）`, `test.mp4（2）`, etc.
Removing a video forgets its binding, while its prior files remain on disk.

Outputs are generated and probed in private storage, copied to staging files
and published with rollback. Local publication uses sibling renames; SAF uses
provider staging and backup renames. A failed restoration retains its backup
rather than discarding the last surviving bytes. Cancellation waits for native
sessions to stop before temporary inputs are released.

## Verification

JVM tests cover queue holes, independent moves, output ownership, row eligibility,
stream maps and audio container selection. `MediaMuxWorkflowTest` exercises the
actual ViewModel with generated media, split/merge, both conflict choices,
per-run decisions, folder conflicts, repeated exports, reimport identity and
unchanged input bytes. Compose tests cover tabs, the directory chooser, gray
row editing, ordering and conflict dialog actions. No tests write user media.
