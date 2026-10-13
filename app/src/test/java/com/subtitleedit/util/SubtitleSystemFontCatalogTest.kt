package com.subtitleedit.util

import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleSystemFontCatalogTest {
    @Test
    fun includesOnlyConfiguredFamiliesWhoseFontFilesAreInstalled() {
        val config = """
            <familyset>
              <family name="Noto Serif">
                <font weight="400">NotoSerif-Regular.ttf</font>
              </family>
              <family name="Missing Desktop Font">
                <font weight="400">Baskerville.ttf</font>
              </family>
              <alias name="serif" to="Noto Serif" />
              <alias name="baskerville" to="Missing Desktop Font" />
            </familyset>
        """.trimIndent()

        val names = SubtitleSystemFontCatalog.parse(
            listOf(config),
            setOf("NotoSerif-Regular.ttf")
        )

        assertEquals(listOf("Noto Serif", "serif"), names)
    }

    @Test
    fun supportsFilenameAttributesAndAliasChains() {
        val config = """
            <familyset>
              <family name="Configured Family">
                <font file="/system/fonts/RealFamily.otf" weight="400" />
              </family>
              <alias name="first-alias" to="Configured Family" />
              <alias name="second-alias" to="first-alias" />
            </familyset>
        """.trimIndent()

        val names = SubtitleSystemFontCatalog.parse(listOf(config), setOf("RealFamily.otf"))

        assertEquals(listOf("Configured Family", "first-alias", "second-alias"), names)
    }

    @Test
    fun resolvesAliasesToInstalledRegularFaceWithoutListingAliasAsFamily() {
        val config = """
            <familyset>
              <family name="Roboto">
                <font weight="700" style="italic">Roboto-BoldItalic.ttf</font>
                <font weight="400">Roboto-Regular.ttf</font>
              </family>
              <alias name="sans-serif" to="Roboto" />
            </familyset>
        """.trimIndent()

        val catalog = SubtitleSystemFontCatalog.resolve(
            listOf(config),
            listOf(
                SubtitleSystemFontCatalog.InstalledFace(
                    name = "Roboto", path = "/system/fonts/Roboto-BoldItalic.ttf",
                    weight = 700, italic = true
                ),
                SubtitleSystemFontCatalog.InstalledFace(
                    name = "Roboto", path = "/system/fonts/Roboto-Regular.ttf",
                    weight = 400, italic = false
                )
            )
        )

        assertEquals(listOf("Roboto"), catalog.choices.map { it.name })
        assertEquals("Roboto-Regular.ttf", catalog.names["sans-serif"]?.path?.substringAfterLast('/'))
        assertEquals(false, catalog.names["sans-serif"]?.italic)
    }
}
