package pl.lewicowyt.notifier.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.lewicowyt.notifier.model.SourceType

/**
 * Katalog musi znieść przyszłe rozszerzenia (np. źródła Twitch/TikTok, których ta
 * wersja jeszcze nie obsługuje) bez wywalania ładowania całości: nieznane źródła i
 * zniekształcone wpisy są pomijane, a reszta katalogu działa.
 */
class CreatorCatalogParsingTest {

    @Test
    fun `unknown source type is skipped, known sources of the same creator survive`() {
        val json = """
            [
              {
                "id": "twZrca",
                "name": "Twórca",
                "sources": [
                  { "type": "TWITCH", "url": "https://twitch.tv/x", "externalId": "x" },
                  { "type": "CHANNEL", "url": "https://youtube.com/x", "externalId": "UCabc" }
                ]
              }
            ]
        """.trimIndent()

        val creators = CreatorCatalog.parseCatalog(json)

        assertEquals(1, creators.size)
        val sources = creators.single().sources
        assertEquals(1, sources.size)
        assertEquals(SourceType.CHANNEL, sources.single().type)
    }

    @Test
    fun `creator whose sources are all unknown platforms is kept but with no sources`() {
        val json = """
            [
              { "id": "only", "name": "Tylko Twitch",
                "sources": [ { "type": "TWITCH", "url": "https://twitch.tv/y", "externalId": "y" } ] }
            ]
        """.trimIndent()

        val creators = CreatorCatalog.parseCatalog(json)

        assertEquals(1, creators.size)
        assertTrue(creators.single().sources.isEmpty())
    }

    @Test
    fun `malformed creator entry is skipped, valid entries still load`() {
        val json = """
            [
              { "name": "Bez id", "sources": [] },
              { "id": "ok", "name": "Poprawny",
                "sources": [ { "type": "CHANNEL", "url": "u", "externalId": "UCok" } ] }
            ]
        """.trimIndent()

        val creators = CreatorCatalog.parseCatalog(json)

        assertEquals(listOf("ok"), creators.map { it.id })
    }

    @Test
    fun `known types parse case-insensitively`() {
        val json = """
            [
              { "id": "c", "name": "C",
                "sources": [ { "type": "playlist", "url": "u", "externalId": "PLxyz" } ] }
            ]
        """.trimIndent()

        val creators = CreatorCatalog.parseCatalog(json)

        assertEquals(SourceType.PLAYLIST, creators.single().sources.single().type)
    }
}
