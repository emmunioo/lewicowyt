package pl.lewicowyt.notifier.links

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.lewicowyt.notifier.data.AppSettings
import pl.lewicowyt.notifier.data.YouTubeLinkTarget
import pl.lewicowyt.notifier.data.parseYouTubeLinkTarget
import pl.lewicowyt.notifier.diagnostics.DiagnosticReasonCode

class YouTubeLinkLauncherTest {
    @Test
    fun `default setting is system and stored value round trips`() {
        assertEquals(YouTubeLinkTarget.SYSTEM_DEFAULT, AppSettings().youtubeLinkTarget)
        YouTubeLinkTarget.entries.forEach { target ->
            assertEquals(target, parseYouTubeLinkTarget(target.name))
        }
        assertEquals(YouTubeLinkTarget.SYSTEM_DEFAULT, parseYouTubeLinkTarget("invalid"))
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
    fun `youtube is preferred when available and falls back when missing`() {
        assertEquals(
            YouTubeLinkRoute.YOUTUBE,
            planYouTubeLinkOpen(
                YouTubeLinkTarget.YOUTUBE,
                available(youtube = true),
            ).route,
        )
        val fallback = planYouTubeLinkOpen(
            YouTubeLinkTarget.YOUTUBE,
            available(youtube = false),
        )
        assertEquals(YouTubeLinkRoute.SYSTEM, fallback.route)
        assertEquals(DiagnosticReasonCode.APP_NOT_AVAILABLE, fallback.fallbackReason)
    }

    @Test
    fun `newpipe is preferred when available and falls back when missing`() {
        assertEquals(
            YouTubeLinkRoute.NEWPIPE,
            planYouTubeLinkOpen(
                YouTubeLinkTarget.NEWPIPE,
                available(newPipe = true),
            ).route,
        )
        val fallback = planYouTubeLinkOpen(
            YouTubeLinkTarget.NEWPIPE,
            available(newPipe = false),
        )
        assertEquals(YouTubeLinkRoute.SYSTEM, fallback.route)
        assertEquals(DiagnosticReasonCode.APP_NOT_AVAILABLE, fallback.fallbackReason)
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
    fun `no application supporting link returns none`() {
        val result = planYouTubeLinkOpen(
            YouTubeLinkTarget.NEWPIPE,
            available(system = false, newPipe = false),
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
        youtube: Boolean = false,
        newPipe: Boolean = false,
        browserPackage: String? = null,
    ) = YouTubeLinkAvailability(system, youtube, newPipe, browserPackage)
}
