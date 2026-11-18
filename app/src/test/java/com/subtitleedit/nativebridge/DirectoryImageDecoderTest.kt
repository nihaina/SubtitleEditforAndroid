package com.subtitleedit.nativebridge

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DirectoryImageDecoderTest {
    @Test fun requestsOneLosslessFrameWithoutResizing() {
        val source = File("原 图[01].jpg")
        val output = File("preview.png")
        val args = DirectoryImageDecoder.arguments(source, output).toList()
        assertEquals(source.absolutePath, args[args.indexOf("-i") + 1])
        assertEquals("1", args[args.indexOf("-frames:v") + 1])
        assertEquals("png", args[args.indexOf("-c:v") + 1])
        assertEquals("rgba", args[args.indexOf("-pix_fmt") + 1])
        assertFalse(args.any { it in setOf("-s", "-vf", "-filter:v", "-max_pixels") })
        assertEquals(output.absolutePath, args.last())
    }

}
