package pl.lewicowyt.notifier.links

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.lewicowyt.notifier.data.AppSettings
import pl.lewicowyt.notifier.data.YouTubeLinkTarget
import pl.lewicowyt.notifier.data.parseYouTubeLinkTarget
import pl.lewicowyt.notifier.data.isSafeAndroidPackageName
import pl.lewicowyt.notifier.diagnostics.DiagnosticReasonCode

class YouTubeLinkLauncherTest {
    @Test
    fun `default setting is system and stored value round trips`() {
        assertEquals(YouTubeLinkTarget.SYSTEM_DEFAULT, AppSettings().youtubeLinkTarget)
        YouTubeLinkTarget.entries.forEach { target ->
            assertEquals(target, parseYouTubeLinkTarget(target.name))
        }
        assertEquals(YouTubeLinkTarget.SYSTEM_DEFAULT, parseYouTubeLinkTarget("invalid"))
        // Usunięte presety (dawne ReVanced/NewPipe) degradują do domyślnego.
        assertEquals(YouTubeLinkTarget.SYSTEM_DEFAULT, parseYouTubeLinkTarget("ALTERNATIVE_YOUTUBE"))
        assertEquals(YouTubeLinkTarget.SYSTEM_DEFAULT, parseYouTubeLinkTarget("NEWPIPE"))
    }

    @Test
    fun `system default uses ordinary action view route`() {
        assertEquals(
            YouTubeLinkRoute.SYSTEM,
            planYouTubeLinkOpen(YouTubeLinkTarget.SYSTEM_DEFAULT, available()).route,
        )
    }

    @Test
    fun `always ask uses chooser`() {
        assertEquals(
            YouTubeLinkRoute.CHOOSER,
            planYouTubeLinkOpen(YouTubeLinkTarget.ALWAYS_ASK, available()).route,
        )
    }

    @Test
    fun `youtube option uses ordinary action view so android default handler wins`() {
        // „YouTube" nie wymusza oficjalnej aplikacji — otwiera link zwykłym
        // ACTION_VIEW, więc trafia tam, gdzie Android kieruje linki YouTube.
        assertEquals(
            YouTubeLinkRoute.SYSTEM,
            planYouTubeLinkOpen(YouTubeLinkTarget.YOUTUBE, available()).route,
        )
        val fallback = planYouTubeLinkOpen(
            YouTubeLinkTarget.YOUTUBE,
            available(system = false),
        )
        assertEquals(YouTubeLinkRoute.NONE, fallback.route)
        assertEquals(DiagnosticReasonCode.NO_LINK_HANDLER, fallback.fallbackReason)
    }

    @Test
    fun `browser route is explicit and browser absence falls back`() {
        val browser = planYouTubeLinkOpen(
            YouTubeLinkTarget.BROWSER,
            available(browserPackage = "org.example.browser"),
        )
        assertEquals(YouTubeLinkRoute.BROWSER, browser.route)
        assertEquals("org.example.browser", browser.packageName)

        val fallback = planYouTubeLinkOpen(
            YouTubeLinkTarget.BROWSER,
            available(browserPackage = null),
        )
        assertEquals(YouTubeLinkRoute.SYSTEM, fallback.route)
        assertEquals(DiagnosticReasonCode.BROWSER_NOT_AVAILABLE, fallback.fallbackReason)
    }

    @Test
    fun `arbitrary selected application receives an explicit route`() {
        val selected = planYouTubeLinkOpen(
            target = YouTubeLinkTarget.OTHER_APP,
            availability = available(),
            otherAppPackage = "com.example.calculator",
        )
        assertEquals(YouTubeLinkRoute.OTHER_APP, selected.route)
        assertEquals("com.example.calculator", selected.packageName)

        val missing = planYouTubeLinkOpen(
            target = YouTubeLinkTarget.OTHER_APP,
            availability = available(),
            otherAppPackage = null,
        )
        assertEquals(YouTubeLinkRoute.NONE, missing.route)
        assertTrue(isSafeAndroidPackageName("com.example.calculator"))
        assertFalse(isSafeAndroidPackageName("calculator"))
    }

    @Test
    fun `no link handler returns none`() {
        val result = planYouTubeLinkOpen(
            YouTubeLinkTarget.SYSTEM_DEFAULT,
            available(system = false),
        )
        assertEquals(YouTubeLinkRoute.NONE, result.route)
        assertEquals(DiagnosticReasonCode.NO_LINK_HANDLER, result.fallbackReason)
    }

    @Test
    fun `video and channel urls are accepted without accepting unsafe urls`() {
        assertTrue(isSafeYouTubeExternalUrl("https://www.youtube.com/watch?v=abcdefghijk"))
        assertTrue(
            isSafeYouTubeExternalUrl(
                "https://www.youtube.com/channel/UC1234567890123456789012",
            ),
        )
        assertTrue(isSafeYouTubeExternalUrl("https://youtu.be/abcdefghijk"))
        assertFalse(isSafeYouTubeExternalUrl("http://www.youtube.com/watch?v=abcdefghijk"))
        assertFalse(isSafeYouTubeExternalUrl("https://youtube.example/watch?v=abcdefghijk"))
    }

    private fun available(
        system: Boolean = true,
        browserPackage: String? = null,
    ) = YouTubeLinkAvailability(
        system = system,
        browserPackage = browserPackage,
    )

    @Test
    fun `D-16 https links with custom port are allowed, unsafe ones are not`() {
        assertTrue(isSafeExternalHttpsUrl("https://serwer.example:2137/artykul"))
        assertTrue(isSafeExternalHttpsUrl("https://example.org/x"))
        assertFalse(isSafeExternalHttpsUrl("http://example.org/x"))
        assertFalse(isSafeExternalHttpsUrl("https://user:pass@example.org/x"))
        assertFalse(isSafeExternalHttpsUrl("https:///brak-hosta"))
    }
}
