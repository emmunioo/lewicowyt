package pl.lewicowyt.notifier.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OlderMaterialJsonParserTest {
    @Test
    fun `parses realistic ytInitialData renderer with Polish text`() {
        val raw = """{"contents":{"items":[{"videoRenderer":{"videoId":"VgOPdcq9oQc","title":{"runs":[{"text":"Pełnomocnicy i Łódź"}]},"descriptionSnippet":{"runs":[{"text":"Zażółć gęślą jaźń"}]}}}]}}"""

        val result = parseOlderMaterialInitialData(raw)

        assertEquals(1, result.size)
        assertEquals("VgOPdcq9oQc", result.single().videoId)
        assertEquals("Pełnomocnicy i Łódź", result.single().title)
        assertEquals("Zażółć gęślą jaźń", result.single().searchEvidence)
    }

    @Test
    fun `rejects very deep JSON before JSONObject parsing`() {
        val raw = nestedObject(MAX_OLDER_SEARCH_JSON_DEPTH + 10)
        assertControlledFailure { parseOlderMaterialInitialData(raw) }
    }

    @Test
    fun `accepts JSON exactly at depth limit`() {
        assertTrue(parseOlderMaterialInitialData(nestedObject(MAX_OLDER_SEARCH_JSON_DEPTH)).isEmpty())
    }

    @Test
    fun `rejects JSON one level above depth limit`() {
        assertControlledFailure {
            parseOlderMaterialInitialData(nestedObject(MAX_OLDER_SEARCH_JSON_DEPTH + 1))
        }
    }

    @Test
    fun `rejects a single overly wide container`() {
        val raw = """{"items":${emptyObjectArray(MAX_OLDER_SEARCH_CONTAINER_ITEMS + 1)}}"""
        assertControlledFailure { parseOlderMaterialInitialData(raw) }
    }

    @Test
    fun `enforces global node budget independently of depth`() {
        val limits = OlderMaterialJsonLimits(
            maxDepth = 10,
            maxNodes = 10,
            maxContainerItems = 20,
            maxCandidates = 5,
            maxJsonChars = 10_000,
        )
        assertControlledFailure {
            parseOlderMaterialInitialData(
                rawJson = """{"items":${emptyObjectArray(10)}}""",
                limits = limits,
            )
        }
    }

    @Test
    fun `thousands of empty objects terminate without candidates`() {
        val groups = (0 until 20).joinToString(",") { emptyObjectArray(100) }
        val result = parseOlderMaterialInitialData("""{"groups":[$groups]}""")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `many irrelevant arrays terminate within node budget`() {
        val groups = (0 until 40).joinToString(",") { "[$it,$it,$it]" }
        assertTrue(parseOlderMaterialInitialData("""{"noise":[$groups]}""").isEmpty())
    }

    @Test
    fun `finds renderer after many irrelevant nodes`() {
        val raw = """{"noise":${emptyObjectArray(500)},"tail":{"videoRenderer":{"videoId":"hua7SlaA85o","title":{"simpleText":"Blachosmrodziarze"}}}}"""
        assertEquals("hua7SlaA85o", parseOlderMaterialInitialData(raw).single().videoId)
    }

    @Test
    fun `early stop avoids walking unrelated tail after candidate limit`() {
        val tooWideTail = emptyObjectArray(MAX_OLDER_SEARCH_CONTAINER_ITEMS + 1)
        val raw = """{"videoRenderer":{"videoId":"hua7SlaA85o","title":{"simpleText":"Film"}},"tail":$tooWideTail}"""

        val result = parseOlderMaterialInitialData(raw, candidateLimit = 1)

        assertEquals(1, result.size)
    }

    @Test
    fun `malformed JSON produces controlled failure`() {
        assertControlledFailure { parseOlderMaterialInitialData("""{"items":]}""") }
    }

    @Test
    fun `missing ytInitialData is a safe empty page`() {
        assertNull(extractOlderMaterialInitialData("<html><body>Brak danych</body></html>"))
    }

    @Test
    fun `incomplete ytInitialData produces controlled failure`() {
        assertControlledFailure {
            extractOlderMaterialInitialData("<script>var ytInitialData = {\"items\":[")
        }
    }

    @Test
    fun `response close to four megabytes is bounded and parseable`() {
        val prefix = "{\"padding\":\""
        val suffix = "\"}"
        val padding = "a".repeat(MAX_OLDER_SEARCH_JSON_CHARS - prefix.length - suffix.length)

        assertTrue(parseOlderMaterialInitialData(prefix + padding + suffix).isEmpty())
    }

    @Test
    fun `response over four megabytes is rejected before parse`() {
        val raw = "{\"padding\":\"" + "a".repeat(MAX_OLDER_SEARCH_JSON_CHARS) + "\"}"
        assertControlledFailure { parseOlderMaterialInitialData(raw) }
    }

    private fun nestedObject(depth: Int): String = buildString {
        repeat(depth - 1) { append("{\"nested\":") }
        append("{}")
        repeat(depth - 1) { append('}') }
    }

    private fun emptyObjectArray(size: Int): String =
        (0 until size).joinToString(prefix = "[", postfix = "]") { "{}" }

    private fun assertControlledFailure(block: () -> Unit) {
        assertThrows(OlderMaterialSearchResponseException::class.java, block)
    }
}
