package com.subtitleedit.util

import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleTranslationResumePolicyTest {
    @Test
    fun resumesFromOneCueBeforeLastValidOutput() {
        assertEquals(0, SubtitleTranslationResumePolicy.resumeIndex(0))
        assertEquals(0, SubtitleTranslationResumePolicy.resumeIndex(1))
        assertEquals(5, SubtitleTranslationResumePolicy.resumeIndex(7))
    }
}
