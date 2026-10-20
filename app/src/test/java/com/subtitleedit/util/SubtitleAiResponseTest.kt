package com.subtitleedit.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.util.concurrent.CancellationException

class SubtitleAiResponseTest {
    @Test
    fun readsAllFencedSubtitleBlocks() {
        val response = "合并结果：\n```text\n123 4\n```\n\n```text\n56\n78\n```"

        assertEquals("123 4\n56\n78", extractSubtitleAiResponse(response))
    }

    @Test
    fun readsAllMarkedSubtitleBlocks() {
        val response = "[[PUNCTUATED_TEXT]]123 4[[/PUNCTUATED_TEXT]]\n" +
            "[[PUNCTUATED_TEXT]]56\n78[[/PUNCTUATED_TEXT]]"

        assertEquals("123 4\n56\n78", extractSubtitleAiResponse(response))
    }

    @Test
    fun plainResponsePreservesLineBreaksAndInternalSpaces() {
        assertEquals("123 4\n56", extractSubtitleAiResponse("\n 123 4\n56\n"))
    }

    @Test
    fun readsAllStartEndTaggedSubtitleBlocks() {
        val response = "说明\n<START>第一段<END>\n" +
            "其他说明\n<start>\n第二段\n<end>"

        assertEquals("第一段\n第二段", extractSubtitleAiResponse(response))
    }

    @Test
    fun taggedBlockPreservesInternalCrLfBlankLinesAndIndentation() {
        val response = "<start>\r\n第一行  \r\n\r\n  第二行\r\n第三行\r\n<end>"

        assertEquals("第一行  \r\n\r\n  第二行\r\n第三行", extractSubtitleAiResponse(response))
    }

    @Test
    fun emptyStartEndTaggedBlockReturnsEmptyText() {
        assertEquals("", extractSubtitleAiResponse("<start>\n\n<end>"))
    }

    @Test
    fun readsStartEndTagsInsideExistingFenceAndMarkedBlocks() {
        val fenced = "```text\n<start>翻译文本<end>\n```"
        val marked = "[[PUNCTUATED_TEXT]]<start>加好标点<end>[[/PUNCTUATED_TEXT]]"

        assertEquals("翻译文本", extractSubtitleAiResponse(fenced))
        assertEquals("加好标点", extractSubtitleAiResponse(marked))
    }

    @Test
    fun incompleteStartEndTagFallsBackToPlainResponse() {
        val response = "说明\n<start>\n正文"

        assertEquals(response, extractSubtitleAiResponse(response))
    }

    @Test
    fun txtResponseRequiresCompleteNonEmptyTaggedContent() {
        assertEquals("译文", extractTaggedTxtAiResponse("说明\n<start>\n译文\n<end>\n结束"))
        assertThrows(InvalidTxtAiResponseException::class.java) {
            extractTaggedTxtAiResponse("说明\n译文")
        }
        assertThrows(InvalidTxtAiResponseException::class.java) {
            extractTaggedTxtAiResponse("<start>\n \n<end>")
        }
        assertThrows(InvalidTxtAiResponseException::class.java) {
            extractTaggedTxtAiResponse("<start>\n正文")
        }
        assertThrows(InvalidTxtAiResponseException::class.java) {
            extractTaggedTxtAiResponse("正文\n<end>")
        }
    }

    @Test
    fun txtResponseExtractsAndJoinsMultipleTaggedBlocks() {
        assertEquals(
            "第一段\n第二段",
            extractTaggedTxtAiResponse("<start>第一段<end>说明<start>\n第二段\n<end>")
        )
    }

    @Test
    fun txtRequestRetriesInvalidTaggedRepliesAtMostThreeTimes() {
        var requestCount = 0
        val error = assertThrows(InvalidTxtAiResponseException::class.java) {
            runBlocking {
                requestTxtAiWithTagRetries(request = {
                    requestCount++
                    extractTaggedTxtAiResponse("没有标签")
                })
            }
        }

        assertEquals(4, requestCount)
        assertTrue(error.message.orEmpty().contains("重试 3 次"))
        assertTrue(error.message.orEmpty().contains("请重试"))
    }

    @Test
    fun txtRequestStopsRetryingWhenTaggedReplyBecomesValid() = runBlocking {
        var requestCount = 0
        var retryCount = 0
        val result = requestTxtAiWithTagRetries(
            request = {
                requestCount++
                extractTaggedTxtAiResponse(
                    if (requestCount == 1) "缺少标签" else "<start>有效译文<end>"
                )
            },
            onRetry = { _, _ -> retryCount++ }
        )

        assertEquals("有效译文", result)
        assertEquals(2, requestCount)
        assertEquals(1, retryCount)
    }

    @Test
    fun txtRequestDoesNotRetryNetworkOrCancellationExceptions() {
        val networkError = IOException("network")
        var networkRequestCount = 0
        val thrownNetworkError = assertThrows(IOException::class.java) {
            runBlocking {
                requestTxtAiWithTagRetries(request = {
                    networkRequestCount++
                    throw networkError
                })
            }
        }

        assertSame(networkError, thrownNetworkError)
        assertEquals(1, networkRequestCount)

        val cancellation = CancellationException("cancelled")
        var cancellationRequestCount = 0
        val thrownCancellation = assertThrows(CancellationException::class.java) {
            runBlocking {
                requestTxtAiWithTagRetries(request = {
                    cancellationRequestCount++
                    throw cancellation
                })
            }
        }

        assertSame(cancellation, thrownCancellation)
        assertEquals(1, cancellationRequestCount)
    }
}
