package com.subtitleedit.util

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * TAR 字节流在测试内手工拼装（512 字节头 + 补齐到 512 的负载 + 两个全零结束块），
 * 这样每条安全防线都能被单独触发，不依赖外部归档文件。
 */
class StreamingTarExtractorTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private var stagingCounter = 0

    // ==================== 正常解包 ====================

    @Test
    fun extract_regularFile_writesStagedPayload() {
        val tar = TarBuilder().file("hello.txt", "你好 TAR".toByteArray()).build()
        val entries = extract(tar)

        assertEquals(1, entries.size)
        val entry = entries[0]
        assertEquals("hello.txt", entry.name)
        assertEquals(false, entry.isDirectory)
        assertEquals("你好 TAR".toByteArray().size.toLong(), entry.size)
        assertEquals(0, entry.index)
        assertNotNull(entry.stagedFile)
        assertEquals("你好 TAR", entry.stagedFile!!.readText())
    }

    @Test
    fun extract_multipleEntries_keepsOrderAndIndex() {
        val tar = TarBuilder()
            .file("a.txt", "AAA".toByteArray())
            .directory("dir/")
            .file("dir/b.txt", "BBBB".toByteArray())
            .build()
        val entries = extract(tar)

        assertEquals(listOf("a.txt", "dir", "dir/b.txt"), entries.map { it.name })
        assertEquals(listOf(0, 1, 2), entries.map { it.index })
        assertEquals("AAA", entries[0].stagedFile!!.readText())
        assertEquals("BBBB", entries[2].stagedFile!!.readText())
    }

    @Test
    fun extract_directoryEntry_hasNoStagedFile() {
        val tar = TarBuilder().directory("docs/").build()
        val entries = extract(tar)

        assertEquals(1, entries.size)
        assertTrue(entries[0].isDirectory)
        assertNull(entries[0].stagedFile)
    }

    @Test
    fun extract_emptyArchive_returnsNoEntries() {
        assertTrue(extract(TarBuilder().build()).isEmpty())
    }

    @Test
    fun extract_shouldExtractFalse_skipsPayloadButKeepsParsing() {
        val tar = TarBuilder()
            .file("skip.bin", ByteArray(1000) { 7 })
            .file("keep.txt", "kept".toByteArray())
            .build()
        val entries = extract(tar, shouldExtract = { it.name != "skip.bin" })

        assertEquals(2, entries.size)
        assertNull(entries[0].stagedFile)
        assertEquals(1000L, entries[0].size)
        assertEquals("kept", entries[1].stagedFile!!.readText())
    }

    @Test
    fun extract_oldStyleTypeFlag_isTreatedAsRegularFile() {
        val tar = TarBuilder().file("legacy.txt", "x".toByteArray(), type = '\u0000').build()
        val entries = extract(tar)

        assertEquals(false, entries[0].isDirectory)
        assertEquals("x", entries[0].stagedFile!!.readText())
    }

    @Test
    fun extract_contiguousTypeFlag_isTreatedAsRegularFile() {
        val tar = TarBuilder().file("cont.txt", "y".toByteArray(), type = '7').build()
        assertEquals("y", extract(tar)[0].stagedFile!!.readText())
    }

    @Test
    fun extract_headerMtime_isConvertedToMillis() {
        val tar = TarBuilder().file("t.txt", "z".toByteArray(), mtimeSeconds = 1_600_000_000L).build()
        assertEquals(1_600_000_000_000L, extract(tar)[0].modifiedTimeMillis)
    }

    @Test
    fun extract_ustarPrefix_isJoinedWithName() {
        val tar = TarBuilder().file("b.txt", "c".toByteArray(), prefix = "outer/inner").build()
        assertEquals("outer/inner/b.txt", extract(tar)[0].name)
    }

    @Test
    fun extract_dotSegments_areNormalizedAway() {
        val tar = TarBuilder().file("./a//./b.txt", "c".toByteArray()).build()
        assertEquals("a/b.txt", extract(tar)[0].name)
    }

    @Test
    fun extract_backslashSeparators_areNormalizedToSlash() {
        val tar = TarBuilder().file("a\\b.txt", "c".toByteArray()).build()
        assertEquals("a/b.txt", extract(tar)[0].name)
    }

    // ==================== 路径安全 ====================

    @Test
    fun extract_pathTraversal_isRejected() {
        assertExtractFails(
            TarBuilder().file("../evil.txt", "x".toByteArray()).build(),
            "TAR 包含路径穿越内容"
        )
    }

    @Test
    fun extract_nestedPathTraversal_isRejected() {
        assertExtractFails(
            TarBuilder().file("a/../../evil.txt", "x".toByteArray()).build(),
            "TAR 包含路径穿越内容"
        )
    }

    @Test
    fun extract_absolutePath_isRejected() {
        assertExtractFails(
            TarBuilder().file("/etc/passwd", "x".toByteArray()).build(),
            "TAR 包含绝对路径"
        )
    }

    @Test
    fun extract_windowsAbsolutePath_isRejected() {
        assertExtractFails(
            TarBuilder().file("C:\\Windows\\evil.txt", "x".toByteArray()).build(),
            "TAR 包含 Windows 绝对路径"
        )
    }

    @Test
    fun extract_controlCharacterInPath_isRejected() {
        assertExtractFails(
            TarBuilder().file("bad\u0001name.txt", "x".toByteArray()).build(),
            "TAR 路径包含控制字符"
        )
    }

    @Test
    fun extract_emptyPath_isRejected() {
        assertExtractFails(
            TarBuilder().file(".", "x".toByteArray()).build(),
            "TAR 条目路径为空"
        )
    }

    @Test
    fun extract_overlongPathComponent_isRejected() {
        val longComponent = "n".repeat(256)
        assertExtractFails(
            TarBuilder().longName("dir/$longComponent").file("placeholder", "x".toByteArray()).build(),
            "TAR 路径分段过长"
        )
    }

    @Test
    fun extract_overlongPath_isRejected() {
        val path = (0 until 20).joinToString("/") { "s".repeat(250) }
        assertExtractFails(
            TarBuilder().longName(path).file("placeholder", "x".toByteArray()).build(),
            "TAR 条目路径过长"
        )
    }

    // ==================== 条目类型 ====================

    @Test
    fun extract_hardLinkEntry_isRejected() {
        assertExtractFails(
            TarBuilder().file("link", ByteArray(0), type = '1').build(),
            "TAR 包含硬链接或符号链接"
        )
    }

    @Test
    fun extract_symbolicLinkEntry_isRejected() {
        assertExtractFails(
            TarBuilder().file("link", ByteArray(0), type = '2').build(),
            "TAR 包含硬链接或符号链接"
        )
    }

    @Test
    fun extract_longLinkNameEntry_isRejected() {
        assertExtractFails(
            TarBuilder().file("././@LongLink", ByteArray(0), type = 'K').build(),
            "TAR 包含硬链接或符号链接"
        )
    }

    @Test
    fun extract_sparseEntry_isRejected() {
        assertExtractFails(
            TarBuilder().file("sparse.bin", ByteArray(0), type = 'S').build(),
            "TAR 稀疏文件不受支持"
        )
    }

    @Test
    fun extract_characterDevice_isRejected() {
        assertExtractFails(
            TarBuilder().file("dev", ByteArray(0), type = '3').build(),
            "TAR 包含不支持的特殊文件类型"
        )
    }

    @Test
    fun extract_fifo_isRejected() {
        assertExtractFails(
            TarBuilder().file("pipe", ByteArray(0), type = '6').build(),
            "TAR 包含不支持的特殊文件类型"
        )
    }

    // ==================== 扩展头 ====================

    @Test
    fun extract_gnuLongName_replacesHeaderName() {
        val longName = "deep/" + "x".repeat(200) + ".txt"
        val tar = TarBuilder().longName(longName).file("truncated.txt", "v".toByteArray()).build()
        assertEquals(longName, extract(tar)[0].name)
    }

    @Test
    fun extract_gnuLongName_appliesOnlyToNextEntry() {
        val tar = TarBuilder()
            .longName("renamed.txt")
            .file("ignored.txt", "1".toByteArray())
            .file("second.txt", "2".toByteArray())
            .build()
        assertEquals(listOf("renamed.txt", "second.txt"), extract(tar).map { it.name })
    }

    @Test
    fun extract_paxPathRecord_overridesHeaderName() {
        val tar = TarBuilder()
            .pax('x', paxRecord("path", "从 PAX 来的/名字.txt"))
            .file("ignored.txt", "v".toByteArray())
            .build()
        assertEquals("从 PAX 来的/名字.txt", extract(tar)[0].name)
    }

    @Test
    fun extract_paxSizeAndMtime_overrideHeaderFields() {
        val payload = "1234567890".toByteArray()
        val tar = TarBuilder()
            .pax('x', paxRecord("size", payload.size.toString()) + paxRecord("mtime", "1234.987"))
            .file("sized.txt", payload, declaredSize = 0L)
            .build()
        val entry = extract(tar)[0]

        assertEquals(payload.size.toLong(), entry.size)
        assertEquals(1_234_987L, entry.modifiedTimeMillis)
        assertEquals("1234567890", entry.stagedFile!!.readText())
    }

    @Test
    fun extract_globalPax_appliesToAllEntriesAndLocalWins() {
        val tar = TarBuilder()
            .pax('g', paxRecord("path", "global.txt"))
            .file("first.txt", "1".toByteArray())
            .pax('x', paxRecord("path", "local.txt"))
            .file("second.txt", "2".toByteArray())
            .file("third.txt", "3".toByteArray())
            .build()
        assertEquals(listOf("global.txt", "local.txt", "global.txt"), extract(tar).map { it.name })
    }

    @Test
    fun extract_paxSparseRecord_isRejected() {
        assertExtractFails(
            TarBuilder()
                .pax('x', paxRecord("GNU.sparse.major", "1"))
                .file("sparse.bin", ByteArray(0))
                .build(),
            "TAR 稀疏文件不受支持"
        )
    }

    @Test
    fun extract_malformedPaxRecordLength_isRejected() {
        assertExtractFails(
            TarBuilder()
                .pax('x', "abc path=a\n".toByteArray(StandardCharsets.UTF_8))
                .file("a.txt", ByteArray(0))
                .build(),
            "PAX 记录长度无效"
        )
    }

    @Test
    fun extract_paxRecordWithoutNewline_isRejected() {
        assertExtractFails(
            TarBuilder()
                .pax('x', "11 path=abcd".toByteArray(StandardCharsets.UTF_8))
                .file("a.txt", ByteArray(0))
                .build(),
            "PAX 记录不完整"
        )
    }

    @Test
    fun extract_oversizedMetadataPayload_isRejected() {
        // 只声明超大长度，防线在读取负载之前就应触发。
        assertExtractFails(
            TarBuilder().rawHeader(header("meta", size = 2 * 1024 * 1024L, type = 'x')).build(),
            "TAR 扩展元数据过大"
        )
    }

    @Test
    fun extract_metadataHeaderWithoutFileEntry_isRejected() {
        assertExtractFails(
            TarBuilder().longName("dangling.txt").build(),
            "TAR 扩展头后缺少文件条目"
        )
    }

    @Test
    fun extract_paxHeaderWithoutFileEntry_isRejected() {
        assertExtractFails(
            TarBuilder().pax('x', paxRecord("path", "dangling.txt")).build(),
            "TAR 扩展头后缺少文件条目"
        )
    }

    @Test
    fun extract_tooManyMetadataHeaders_isRejected() {
        // 允许量 = maxEntries * 4 + 1024，此处 maxEntries=1 即 1028 个。
        val builder = TarBuilder()
        repeat(1029) { builder.pax('g', ByteArray(0)) }
        assertExtractFails(builder.file("a.txt", ByteArray(0)).build(), "TAR 扩展头数量超过安全限制", maxEntries = 1)
    }

    // ==================== 配额与长度 ====================

    @Test
    fun extract_tooManyEntries_isRejected() {
        val tar = TarBuilder()
            .file("a.txt", ByteArray(0))
            .file("b.txt", ByteArray(0))
            .build()
        assertExtractFails(tar, "TAR 条目数量超过安全限制", maxEntries = 1)
    }

    @Test
    fun extract_entryCountAtLimit_isAccepted() {
        val tar = TarBuilder().file("a.txt", ByteArray(0)).build()
        assertEquals(1, extract(tar, maxEntries = 1).size)
    }

    @Test
    fun extract_payloadOverMaxBytes_isRejected() {
        // 声明的负载大小超过配额，防线在读取负载之前触发。
        assertExtractFails(
            TarBuilder().rawHeader(header("big.bin", size = 4096L)).build(),
            "TAR 数据超过安全大小限制",
            maxBytes = 1024L
        )
    }

    @Test
    fun extract_accumulatedPayloadOverMaxBytes_isRejected() {
        val tar = TarBuilder()
            .file("a.bin", ByteArray(600))
            .rawHeader(header("b.bin", size = 600L))
            .build()
        assertExtractFails(tar, "TAR 数据超过安全大小限制", maxBytes = 1024L)
    }

    @Test
    fun extract_expectedSizeOverLimit_isRejectedBeforeReading() {
        assertExtractFails(
            TarBuilder().build(),
            "TAR 数据超过安全大小限制",
            maxBytes = 1L,
            expectedTarBytes = 64L * 1024 * 1024 + 2
        )
    }

    @Test
    fun extract_expectedSizeMismatch_isRejected() {
        val tar = TarBuilder().file("a.txt", "x".toByteArray()).build()
        assertExtractFails(tar, "TAR 数据长度与预期不符", expectedTarBytes = tar.size + 512L)
    }

    @Test
    fun extract_dataLongerThanExpected_isRejected() {
        val tar = TarBuilder().file("a.txt", "x".toByteArray()).build()
        assertExtractFails(tar, "TAR 数据超过预期长度", expectedTarBytes = 512L)
    }

    @Test
    fun extract_validateExpectedSizeFalse_ignoresLengthMismatch() {
        val tar = TarBuilder().file("a.txt", "x".toByteArray()).build()
        val entries = extract(
            tar,
            expectedTarBytes = 4L,
            validateExpectedSize = false
        )
        assertEquals(1, entries.size)
    }

    @Test
    fun extract_truncatedStream_isRejected() {
        val tar = TarBuilder().file("a.txt", "x".toByteArray()).build()
        val truncated = tar.copyOf(tar.size - 600)
        assertExtractFails(
            truncated,
            "TAR 数据提前结束",
            expectedTarBytes = truncated.size.toLong()
        )
    }

    @Test
    fun extract_missingTrailer_isRejected() {
        val tar = TarBuilder().file("a.txt", "x".toByteArray()).buildWithoutTrailer()
        assertExtractFails(tar, "TAR 数据提前结束")
    }

    @Test
    fun extract_incompleteTrailer_isRejected() {
        val tar = TarBuilder()
            .rawHeader(ByteArray(512))
            .file("a.txt", "x".toByteArray())
            .build()
        assertExtractFails(tar, "TAR 结束标记不完整")
    }

    @Test
    fun extract_nonZeroDataAfterTrailer_isRejected() {
        val tar = TarBuilder().file("a.txt", "x".toByteArray()).build() + ByteArray(16) { 9 }
        assertExtractFails(tar, "TAR 结束标记后包含非零数据")
    }

    // ==================== 头部字段 ====================

    @Test
    fun extract_badHeaderChecksum_isRejected() {
        assertExtractFails(
            TarBuilder().rawHeader(header("a.txt", corruptChecksum = true)).build(),
            "TAR 文件头校验失败"
        )
    }

    @Test
    fun extract_nonOctalSizeField_isRejected() {
        val block = header("a.txt")
        block[126] = '9'.code.toByte()
        assertExtractFails(TarBuilder().rawHeader(rechecksum(block)).build(), "TAR 文件大小字段无效")
    }

    // ==================== staging ====================

    @Test
    fun extract_existingStagedFile_isNotOverwritten() {
        val staging = newStaging()
        File(staging, "payload-0").writeText("已存在")
        val tar = TarBuilder().file("a.txt", "new".toByteArray()).build()

        val failure = runCatching {
            StreamingTarExtractor.extract(
                input = ByteArrayInputStream(tar),
                staging = staging,
                expectedTarBytes = tar.size.toLong(),
                maxEntries = 8,
                maxBytes = 1L shl 20
            )
        }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertTrue(failure!!.message.orEmpty().contains("TAR 目标文件已存在"))
        assertEquals("已存在", File(staging, "payload-0").readText())
    }

    @Test
    fun extract_stagingIsFile_isRejected() {
        val staging = File(temporaryFolder.newFolder(), "not-a-dir").apply { writeText("x") }
        val tar = TarBuilder().build()

        val failure = runCatching {
            StreamingTarExtractor.extract(
                input = ByteArrayInputStream(tar),
                staging = staging,
                expectedTarBytes = tar.size.toLong(),
                maxEntries = 8,
                maxBytes = 1L shl 20
            )
        }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertTrue(failure!!.message.orEmpty().contains("TAR staging 不是目录"))
    }

    @Test
    fun extract_missingStagingDirectory_isCreated() {
        val staging = File(temporaryFolder.newFolder(), "nested/staging")
        val tar = TarBuilder().file("a.txt", "x".toByteArray()).build()

        StreamingTarExtractor.extract(
            input = ByteArrayInputStream(tar),
            staging = staging,
            expectedTarBytes = tar.size.toLong(),
            maxEntries = 8,
            maxBytes = 1L shl 20
        )

        assertTrue(staging.isDirectory)
    }

    // ==================== 参数校验与回调 ====================

    @Test
    fun extract_invalidArguments_throwIllegalArgument() {
        val tar = TarBuilder().build()
        assertIllegalArgument { extract(tar, expectedTarBytes = -1L) }
        assertIllegalArgument { extract(tar, maxEntries = 0) }
        assertIllegalArgument { extract(tar, maxBytes = 0L) }
    }

    @Test
    fun extract_onEntry_seesCandidateBeforeStaging() {
        val seen = mutableListOf<Pair<String, File?>>()
        val tar = TarBuilder().file("a.txt", "x".toByteArray()).build()
        extract(tar, onEntry = { seen += it.name to it.stagedFile })

        assertEquals(1, seen.size)
        assertEquals("a.txt", seen[0].first)
        assertNull(seen[0].second)
    }

    @Test
    fun extract_onProgress_reportsFinalConsumedBytes() {
        val progress = mutableListOf<Pair<Long, Long>>()
        val tar = TarBuilder().file("a.txt", "x".toByteArray()).build()
        extract(tar, onProgress = { consumed, total -> progress += consumed to total })

        assertEquals(0L to tar.size.toLong(), progress.first())
        assertEquals(tar.size.toLong() to tar.size.toLong(), progress.last())
    }

    @Test
    fun extract_checkCancelled_propagatesException() {
        val tar = TarBuilder().file("a.txt", "x".toByteArray()).build()
        try {
            extract(tar, checkCancelled = { throw IllegalStateException("已取消") })
            fail("应当抛出取消异常")
        } catch (e: IllegalStateException) {
            assertEquals("已取消", e.message)
        }
    }

    @Test
    fun extract_directOutput_resolvesEachDestinationAfterPreviousFileIsWritten() {
        val destination = temporaryFolder.newFolder("direct-output")
        val staging = File(destination, "unused-staging")
        val events = mutableListOf<String>()
        val tar = TarBuilder()
            .file("first.txt", "first payload".toByteArray())
            .directory("nested/")
            .file("nested/second.txt", "second payload".toByteArray())
            .build()

        val entries = StreamingTarExtractor.extract(
            input = ByteArrayInputStream(tar),
            staging = staging,
            expectedTarBytes = tar.size.toLong(),
            maxEntries = 8,
            maxBytes = 1L shl 20,
            openOutput = { entry ->
                events += "open:${entry.name}"
                if (entry.name == "nested/second.txt") {
                    // A conflict resolver can inspect the current destination immediately;
                    // the earlier output already exists before the next header is handled.
                    assertEquals("first payload", File(destination, "first.txt").readText())
                    assertEquals("existing second", File(destination, entry.name).readText())
                }
                val target = File(destination, entry.name)
                if (entry.isDirectory) {
                    target.mkdir()
                    File(target, "second.txt").writeText("existing second")
                    null
                } else {
                    val resolved = if (target.exists()) File(target.parentFile, "second (1).txt") else target
                    resolved.outputStream()
                }
            },
            onEntryComplete = { entry, success ->
                assertTrue(success)
                events += "complete:${entry.name}"
            }
        )

        assertFalse(staging.exists())
        assertTrue(entries.all { it.stagedFile == null })
        assertEquals("second payload", File(destination, "nested/second (1).txt").readText())
        assertEquals("existing second", File(destination, "nested/second.txt").readText())
        assertEquals(
            listOf(
                "open:first.txt", "complete:first.txt", "open:nested", "complete:nested",
                "open:nested/second.txt", "complete:nested/second.txt"
            ),
            events
        )
    }

    @Test
    fun extract_directOutput_nullStreamSkipsFileAndContinues() {
        val destination = temporaryFolder.newFolder("direct-skip")
        val existing = File(destination, "skip.txt").apply { writeText("existing") }
        val tar = TarBuilder()
            .file("skip.txt", ByteArray(1000) { 7 })
            .file("keep.txt", "kept".toByteArray())
            .build()
        val completed = mutableListOf<Pair<String, Boolean>>()

        val entries = StreamingTarExtractor.extract(
            input = ByteArrayInputStream(tar),
            staging = File(destination, "unused-staging"),
            expectedTarBytes = tar.size.toLong(),
            maxEntries = 8,
            maxBytes = 1L shl 20,
            openOutput = { entry ->
                if (entry.name == "skip.txt") null else File(destination, entry.name).outputStream()
            },
            onEntryComplete = { entry, success -> completed += entry.name to success }
        )

        assertEquals("existing", existing.readText())
        assertEquals("kept", File(destination, "keep.txt").readText())
        assertEquals(listOf("skip.txt" to true, "keep.txt" to true), completed)
        assertEquals(2, entries.size)
    }

    @Test
    fun extract_directOutput_truncatedPayloadClosesStreamBeforeFailureCallback() {
        val tar = TarBuilder()
            .rawHeader(header("truncated.bin", size = 1024L))
            .payload(ByteArray(5))
            .buildWithoutTrailer()
        var closed = false
        val completions = mutableListOf<Boolean>()
        val output = object : ByteArrayOutputStream() {
            override fun close() {
                closed = true
                super.close()
            }
        }

        val failure = runCatching {
            StreamingTarExtractor.extract(
                input = ByteArrayInputStream(tar),
                staging = File(temporaryFolder.root, "unused-staging"),
                expectedTarBytes = 0L,
                maxEntries = 8,
                maxBytes = 1L shl 20,
                openOutput = { output },
                onEntryComplete = { _, success ->
                    assertTrue(closed)
                    completions += success
                }
            )
        }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertTrue(failure!!.message.orEmpty().contains("TAR 数据提前结束"))
        assertTrue(closed)
        assertEquals(listOf(false), completions)
    }

    @Test
    fun extract_directOutput_cancelledPayloadClosesStreamAndPreservesOriginalFailure() {
        val tar = TarBuilder().file("cancelled.bin", ByteArray(1000)).build()
        var opened = false
        var closed = false
        var failedEntry: String? = null
        val output = object : OutputStream() {
            override fun write(value: Int) = Unit
            override fun close() { closed = true }
        }

        val failure = runCatching {
            StreamingTarExtractor.extract(
                input = ByteArrayInputStream(tar),
                staging = File(temporaryFolder.root, "unused-staging"),
                expectedTarBytes = tar.size.toLong(),
                maxEntries = 8,
                maxBytes = 1L shl 20,
                checkCancelled = { if (opened) throw IllegalStateException("cancelled") },
                openOutput = { opened = true; output },
                onEntryComplete = { entry, success ->
                    assertTrue(closed)
                    assertFalse(success)
                    failedEntry = entry.name
                    throw IOException("cleanup failed")
                }
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals("cancelled", failure!!.message)
        assertEquals("cancelled.bin", failedEntry)
        assertEquals("cleanup failed", failure.suppressed.single().message)
    }

    // ==================== 辅助 ====================

    private fun newStaging(): File = temporaryFolder.newFolder("staging-${stagingCounter++}")

    private fun extract(
        tar: ByteArray,
        maxEntries: Int = 64,
        maxBytes: Long = 1L shl 20,
        expectedTarBytes: Long = tar.size.toLong(),
        validateExpectedSize: Boolean = true,
        shouldExtract: (StreamingTarExtractor.Entry) -> Boolean = { true },
        checkCancelled: () -> Unit = {},
        onEntry: (StreamingTarExtractor.Entry) -> Unit = {},
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): List<StreamingTarExtractor.Entry> = StreamingTarExtractor.extract(
        input = ByteArrayInputStream(tar),
        staging = newStaging(),
        expectedTarBytes = expectedTarBytes,
        maxEntries = maxEntries,
        maxBytes = maxBytes,
        validateExpectedSize = validateExpectedSize,
        shouldExtract = shouldExtract,
        checkCancelled = checkCancelled,
        onEntry = onEntry,
        onProgress = onProgress
    )

    private fun assertExtractFails(
        tar: ByteArray,
        messagePart: String,
        maxEntries: Int = 64,
        maxBytes: Long = 1L shl 20,
        expectedTarBytes: Long = tar.size.toLong()
    ) {
        val failure = runCatching {
            extract(
                tar,
                maxEntries = maxEntries,
                maxBytes = maxBytes,
                expectedTarBytes = expectedTarBytes
            )
        }.exceptionOrNull()

        assertNotNull("应当抛出 IOException：$messagePart", failure)
        assertTrue("异常类型不符：$failure", failure is IOException)
        assertTrue(
            "异常信息不符，期望包含「$messagePart」，实际为「${failure!!.message}」",
            failure.message.orEmpty().contains(messagePart)
        )
    }

    private fun assertIllegalArgument(block: () -> Unit) {
        val failure = runCatching(block).exceptionOrNull()
        assertTrue("应当抛出 IllegalArgumentException，实际为 $failure", failure is IllegalArgumentException)
    }

    private fun paxRecord(key: String, value: String): ByteArray {
        val body = "$key=$value\n".toByteArray(StandardCharsets.UTF_8)
        var length = body.size + 2
        while (true) {
            val candidate = length.toString().length + 1 + body.size
            if (candidate == length) break
            length = candidate
        }
        return "$length ".toByteArray(StandardCharsets.US_ASCII) + body
    }

    private class TarBuilder {
        private val out = ByteArrayOutputStream()

        fun rawHeader(block: ByteArray): TarBuilder {
            out.write(block)
            return this
        }

        fun payload(data: ByteArray): TarBuilder {
            out.write(data)
            val padding = (BLOCK_SIZE - data.size % BLOCK_SIZE) % BLOCK_SIZE
            out.write(ByteArray(padding))
            return this
        }

        fun file(
            name: String,
            content: ByteArray,
            type: Char = '0',
            mtimeSeconds: Long = 0L,
            prefix: String = "",
            declaredSize: Long = content.size.toLong()
        ): TarBuilder {
            rawHeader(header(name, declaredSize, type, mtimeSeconds, prefix))
            return payload(content)
        }

        fun directory(name: String): TarBuilder = file(name, ByteArray(0), type = '5')

        fun longName(name: String): TarBuilder {
            val raw = name.toByteArray(StandardCharsets.UTF_8) + byteArrayOf(0)
            rawHeader(header("././@LongLink", raw.size.toLong(), 'L'))
            return payload(raw)
        }

        fun pax(type: Char, records: ByteArray): TarBuilder {
            rawHeader(header("PaxHeaders/0", records.size.toLong(), type))
            return payload(records)
        }

        fun build(): ByteArray = buildWithoutTrailer() + ByteArray(BLOCK_SIZE * 2)

        fun buildWithoutTrailer(): ByteArray = out.toByteArray()
    }

    private companion object {
        const val BLOCK_SIZE = 512

        fun header(
            name: String,
            size: Long = 0L,
            type: Char = '0',
            mtimeSeconds: Long = 0L,
            prefix: String = "",
            corruptChecksum: Boolean = false
        ): ByteArray {
            val block = ByteArray(BLOCK_SIZE)
            putBytes(block, 0, name.toByteArray(StandardCharsets.UTF_8), 100)
            putOctal(block, 100, 8, 420L)
            putOctal(block, 108, 8, 0L)
            putOctal(block, 116, 8, 0L)
            putOctal(block, 124, 12, size)
            putOctal(block, 136, 12, mtimeSeconds)
            block[156] = type.code.toByte()
            putBytes(block, 257, "ustar".toByteArray(StandardCharsets.US_ASCII), 6)
            block[263] = '0'.code.toByte()
            block[264] = '0'.code.toByte()
            putBytes(block, 345, prefix.toByteArray(StandardCharsets.UTF_8), 155)
            return rechecksum(block, corruptChecksum)
        }

        fun rechecksum(block: ByteArray, corrupt: Boolean = false): ByteArray {
            for (index in 148..155) block[index] = ' '.code.toByte()
            var sum = 0L
            block.forEach { sum += (it.toInt() and 0xff).toLong() }
            if (corrupt) sum += 1L
            putOctal(block, 148, 7, sum)
            block[155] = ' '.code.toByte()
            return block
        }

        /** 右对齐的八进制字段，末位补 NUL —— 与 GNU tar 的写法一致。 */
        fun putOctal(block: ByteArray, offset: Int, length: Int, value: Long) {
            val digits = value.toString(8).padStart(length - 1, '0')
            putBytes(block, offset, digits.toByteArray(StandardCharsets.US_ASCII), length - 1)
            block[offset + length - 1] = 0
        }

        fun putBytes(block: ByteArray, offset: Int, data: ByteArray, maxLength: Int) {
            val count = minOf(data.size, maxLength)
            data.copyInto(block, offset, 0, count)
        }
    }
}
