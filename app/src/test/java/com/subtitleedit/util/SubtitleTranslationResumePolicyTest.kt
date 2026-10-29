package com.subtitleedit.util

import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleTranslationResumePolicyTest {
    @Test
    fun resumesFromTheCueAfterTheLastValidOutput() {
        assertEquals(0, SubtitleTranslationResumePolicy.resumeIndex(0))
        assertEquals(1, SubtitleTranslationResumePolicy.resumeIndex(1))
        assertEquals(7, SubtitleTranslationResumePolicy.resumeIndex(7))
    }
}
