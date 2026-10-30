package app.getknit.knit.mesh.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The language registry a chat frame's `lang` and `reads` index into (ADR 2026-10.t2h6's 2026-10-29 amendment). Wire
 * law shared with Knit for iOS: append only, an index never moves.
 */
class LanguageRegistryTest {
    @Test
    fun theIndicesArePinnedAndOnlyGrowAtTheEnd() {
        // Append to this list with the registry; never reorder or change an entry: a frame already in custody, or a
        // phone on an older build, reads the index as its language.
        val pinned =
            "en es uk fr pt de ru ar zh zh_hant hi bn ja id tr vi ko it fa pl ur nl ro el cs hu sv he th ms tl ta te mr gu " +
                "kn ml da fi no nb nn sk bg hr sr bs hbs sl lt lv et ca gl eu sq az be is af ug"
        assertEquals(pinned.split(' '), LanguageRegistry.CODES)
        assertEquals(LanguageRegistry.CODES.size, LanguageRegistry.CODES.toSet().size)
    }

    @Test
    fun theLikeliestLanguagesTakeOneByte() {
        for (code in listOf("en", "es", "uk", "fr", "pt", "de", "ru", "ar", "zh")) {
            assertEquals(code, 1, LanguageRegistry.valueBytes(LanguageRegistry.indexOf(code)))
        }
        assertEquals(2, LanguageRegistry.valueBytes(LanguageRegistry.indexOf("ug")))
        assertEquals(0, LanguageRegistry.valueBytes(null))
    }

    @Test
    fun anIndexThisPhoneDoesNotKnowReadsAsNothing() {
        assertEquals("es", LanguageRegistry.codeAt(1))
        assertNull(LanguageRegistry.codeAt(LanguageRegistry.CODES.size))
        assertNull(LanguageRegistry.codeAt(-1))
        assertNull(LanguageRegistry.codeAt(null))
        assertNull(LanguageRegistry.indexOf("tlh"))
    }

    @Test
    fun aDeclarationLeavesOutWhatTheWireImplies() {
        // English by an English reader, or nothing known: nothing declared, so the frame is the bytes it was.
        assertEquals(DeclaredLangs.NONE, DeclaredLangs.of("en", "en"))
        assertEquals(DeclaredLangs.NONE, DeclaredLangs.of(null, null))
        assertEquals(DeclaredLangs.NONE, DeclaredLangs.of(null, "en"))
        // Spanish by a Spanish reader: the body's language alone says both.
        assertEquals(DeclaredLangs(lang = "es"), DeclaredLangs.of("es", "es"))
        // English translated from a Spanish reader's draft: only what they read.
        assertEquals(DeclaredLangs(reads = "es"), DeclaredLangs.of("en", "es"))
        // Spanish from an English reader: both.
        assertEquals(DeclaredLangs("es", "en"), DeclaredLangs.of("es", "en"))
        // A language the registry does not name is never declared.
        assertEquals(DeclaredLangs(reads = "es"), DeclaredLangs.of("tlh", "es"))
    }

    @Test
    fun aDeclarationCostsTheKeyAndOneByteEach() {
        assertEquals(0, DeclaredLangs.NONE.wireCost(named = true))
        assertEquals(3, DeclaredLangs(lang = "es").wireCost(named = true))
        assertEquals(2, DeclaredLangs(lang = "es").wireCost(named = false))
        assertEquals(6, DeclaredLangs("es", "en").wireCost(named = true))
        assertEquals(4, DeclaredLangs("es", "en").wireCost(named = false))
    }

    @Test
    fun aFramesIndicesReadBackAsCodesAndAnUnknownOneIsDropped() {
        assertEquals(DeclaredLangs("es", "en"), DeclaredLangs.fromWire(1, 0))
        assertEquals(DeclaredLangs(lang = "uk"), DeclaredLangs.fromWire(2, 200))
        assertEquals(DeclaredLangs.NONE, DeclaredLangs.fromWire(null, null))
    }

    @Test
    fun aRoomPostDeclaringNothingIsTheBytesItWasAndOneDeclaringCostsWhatItSays() {
        val plain = WireCodec.encodePayload(ChatContent(body = "hola"))
        val declared = WireCodec.encodePayload(ChatContent(body = "hola", lang = LanguageRegistry.indexOf("es")))
        val both = WireCodec.encodePayload(ChatContent(body = "hola", lang = 1, reads = 0))
        assertEquals(plain.size + 3, declared.size)
        assertEquals(plain.size + 6, both.size)
        val read = checkNotNull(WireCodec.decodePayload<ChatContent>(both))
        assertEquals(1, read.lang)
        assertEquals(0, read.reads)
    }

    @Test
    fun aDetectorsOrALocalesCodeReadsAsTheRegistrySpellsIt() {
        assertEquals("es", LanguageRegistry.codeOf("ES-mx"))
        assertEquals("es", LanguageRegistry.codeOf("es-Latn-419"))
        assertEquals("zh_hant", LanguageRegistry.codeOf("zh-Hant"))
        assertEquals("zh", LanguageRegistry.codeOf("zh-Hans-CN"))
        assertEquals("he", LanguageRegistry.codeOf("iw"))
        assertEquals("id", LanguageRegistry.codeOf("in"))
        assertNull(LanguageRegistry.codeOf("un"))
        assertNull(LanguageRegistry.codeOf(""))
    }
}
