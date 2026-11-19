package com.subtitleedit.translation

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.subtitleedit.translation.PageTranslationLanguage.Companion.CHINESE
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PageTranslationLanguageIdentifierTest {
    @Test fun bundledModelIdentifiesLanguagesOtherThanTheDefaultsAndSystemLanguage() = runBlocking {
        withTimeout(20_000) {
            PageTranslationLanguageIdentifier().use { identifier ->
                assertEquals(PageTranslationLanguage("fr"), identifier.identify("Une promenade sous la pluie du soir", CHINESE))
                assertEquals(PageTranslationLanguage("ko"), identifier.identify("비 오는 날에 듣기 좋은 음악", CHINESE))
                assertEquals(PageTranslationLanguage("ru"), identifier.identify("Музыка для прогулки под дождём", CHINESE))
                assertNull(identifier.identify("AB 01", CHINESE))
            }
        }
    }
}
