package com.subtitleedit.filepicker

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FilePickerPolicyTest {
    @Test fun textAndMediaWildcardsUseTheApplicationsExistingExtensionFamilies() {
        for (extension in listOf("SRT", "lrc", "txt", "vtt", "ass", "ssa")) {
            assertTrue(extension, FilePickerPolicy.matchesMimeType(File("sample.$extension"), listOf("text/*")))
        }
        for (extension in listOf("mp3", "wav", "flac", "ape", "ogg", "wma")) {
            assertTrue(extension, FilePickerPolicy.matchesMimeType(File("sample.$extension"), listOf("audio/*")))
        }
        for (extension in listOf("mp4", "mkv", "avi", "mts", "m2ts")) {
            assertTrue(extension, FilePickerPolicy.matchesMimeType(File("sample.$extension"), listOf("video/*")))
        }
    }

    @Test fun exactMimeTypesAndUnknownFilesKeepTheirSeparateFilters() {
        assertTrue(FilePickerPolicy.matchesMimeType(File("backup.json"), listOf("application/json")))
        assertFalse(FilePickerPolicy.matchesMimeType(File("backup.json"), listOf("audio/*", "video/*")))
        assertTrue(FilePickerPolicy.matchesMimeType(File("encoder.onnx"), listOf("application/octet-stream")))
        assertTrue(FilePickerPolicy.matchesMimeType(File("file.unusual"), listOf("*/*")))
        assertTrue(FilePickerPolicy.matchesMime("TEXT/PLAIN", listOf("text/*")))
        assertFalse(FilePickerPolicy.matchesMime("application/json", listOf("text/*")))
    }

    @Test fun hiddenNamesAndUnicodeAreValidWhileTraversalAndEmptyNamesAreRejected() {
        for (name in listOf(".srt", ".hidden", "文件 #1.json", "sample name.srt")) {
            assertTrue(name, FilePickerPolicy.validateFileName(name))
        }
        for (name in listOf("", " ", ".", "..", "../elsewhere", "/absolute", "nested/file", "a\u0000b")) {
            assertFalse(name, FilePickerPolicy.validateFileName(name))
        }
    }
}
