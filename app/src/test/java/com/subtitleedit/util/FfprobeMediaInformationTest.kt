package com.subtitleedit.util

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File

class FfprobeMediaInformationTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun nativeJsonIsReadFromItsOutputFileWithoutAnySessionLogs() {
        val input = "/storage/emulated/0/Java Full Course ☕.mp4"
        lateinit var output: File
        val info = FfprobeMediaInformation.read(input, temporary.root) { arguments ->
            assertEquals(input, arguments.last())
            output = File(arguments[arguments.indexOf("-o") + 1])
            assertEquals(temporary.root.canonicalFile, requireNotNull(output.parentFile).canonicalFile)
            output.writeText("""{"streams":[{"index":0,"codec_type":"subtitle","codec_name":"subrip"}]}""")
            // FFmpeg 8 emits JSON to stdout; the session's log callback receives no JSON.
        }
        assertEquals("subrip", info.getStreams().single().getCodec())
        assertFalse(output.exists())
    }

    @Test fun failedOrEmptyProbesRemoveTheirPrivateJsonAndKeepTheSource() {
        val source = temporary.newFile("source.mp4").apply { writeText("Original source") }
        for (fails in listOf(true, false)) {
            try {
                FfprobeMediaInformation.read(source.path, temporary.root) { arguments ->
                    if (fails) {
                        File(arguments[arguments.indexOf("-o") + 1]).writeText("partial output")
                        error("Probe failure")
                    }
                }
                fail("Failed or empty native probe was accepted")
            } catch (_: IllegalStateException) { }
            assertEquals("Original source", source.readText())
            assertEquals(listOf(source.name), temporary.root.list().orEmpty().toList())
        }
    }

    @Test fun overlappingProbesHaveSeparateOutputFiles() {
        lateinit var first: File
        FfprobeMediaInformation.read("first.mp4", temporary.root) { arguments ->
            first = File(arguments[arguments.indexOf("-o") + 1])
            val second = FfprobeMediaInformation.read("second.srt", temporary.root) { nested ->
                val output = File(nested[nested.indexOf("-o") + 1])
                assertNotEquals(first, output)
                output.writeText("""{"streams":[{"index":0,"codec_type":"subtitle","codec_name":"subrip"}]}""")
            }
            assertEquals("subrip", second.getStreams().single().getCodec())
            assertTrue(first.exists())
            first.writeText("""{"streams":[{"index":0,"codec_type":"video","codec_name":"h264"}]}""")
        }
        assertTrue(temporary.root.list().orEmpty().isEmpty())
    }

    @Test fun mediaJsonRetainsStreamIndicesCodecsAndSourceMeasurements() {
        val info = FfprobeMediaInformation.parse("""
            {
              "streams": [
                {"index": 0, "codec_type": "video", "codec_name": "h264", "width": 1920, "height": 1080,
                 "avg_frame_rate": "30000/1001", "pix_fmt": "yuv420p", "bit_rate": "3000000"},
                {"index": 1, "codec_type": "audio", "codec_name": "aac", "sample_rate": "48000", "channels": 2,
                 "channel_layout": "stereo", "bit_rate": "192000"},
                {"index": 3, "codec_type": "subtitle", "codec_name": "subrip", "tags": {"language": "zho", "title": "字幕 🌊"}}
              ],
              "chapters": [],
              "format": {"format_name": "mov,mp4,m4a,3gp,3g2,mj2", "duration": "42.125", "bit_rate": "3200000"}
            }
        """.trimIndent())
        assertEquals("42.125", info.getDuration())
        assertEquals("3200000", info.getBitrate())
        assertEquals(listOf(0L, 1L, 3L), info.getStreams().map { it.getIndex() })
        assertEquals(listOf("h264", "aac", "subrip"), info.getStreams().map { it.getCodec() })
        assertEquals(1920L, info.getStreams()[0].getWidth())
        assertEquals("30000/1001", info.getStreams()[0].getAverageFrameRate())
        assertEquals("48000", info.getStreams()[1].getSampleRate())
        assertEquals("stereo", info.getStreams()[1].getChannelLayout())
        assertEquals("字幕 🌊", info.getStreams()[2].getTags()?.getString("title"))
    }

    @Test fun standaloneSubtitleJsonNeedsNoFormatOrAudioStream() {
        val info = FfprobeMediaInformation.parse("""{"streams":[{"index":0,"codec_type":"subtitle","codec_name":"subrip"}]}""")
        assertEquals("subtitle", info.getStreams().single().getType())
        assertEquals("subrip", info.getStreams().single().getCodec())
        assertNull(info.getDuration())
    }

    @Test fun leadingAndTrailingDiagnosticsDoNotHideTheJsonObject() {
        val result = FfprobeMediaInformation.parse("Warning {broken diagnostic}\r\n" +
            """{"streams":[{"index":2,"codec_type":"subtitle","codec_name":"webvtt"}]}""" +
            "\r\nTrailing {diagnostic}")
        assertEquals(2L, result.getStreams().single().getIndex())
        assertEquals("webvtt", result.getStreams().single().getCodec())
    }

    @Test fun bracesAndEscapedQuotesInUnicodeMetadataRemainUnchanged() {
        val title = "字幕 {第一段} \\\"quoted\\\" 🌊"
        val filename = "/storage/emulated/0/ADM/Java Full Course for free ☕.mp4"
        val json = JSONObject().put("streams", JSONArray().put(JSONObject()
            .put("index", 0).put("codec_type", "audio").put("codec_name", "aac")))
            .put("format", JSONObject().put("filename", filename).put("tags", JSONObject().put("title", title)))
        val info = FfprobeMediaInformation.parse("\uFEFF\n$json\n")
        assertEquals(filename, info.getFilename())
        assertEquals(title, info.getTags()?.getString("title"))
    }

    @Test fun emptyMalformedOrStreamlessOutputIsRejected() {
        for (output in listOf("", " ", "No such file or directory", "{\"streams\": [", "{}",
            "{\"streams\":[]}", "{\"streams\":[{}]}", "{\"streams\":[{\"index\":-1,\"codec_type\":\"video\"}]}")) {
            try {
                FfprobeMediaInformation.parse(output)
                fail("Invalid output was accepted: $output")
            } catch (_: IllegalStateException) { }
        }
    }
}
