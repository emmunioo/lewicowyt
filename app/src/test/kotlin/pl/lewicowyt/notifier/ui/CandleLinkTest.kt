package pl.lewicowyt.notifier.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** J-10: WebView świeczki pobiera zasoby tylko z hosta strony po https. */
class CandleLinkTest {
    @Test
    fun `only https resources from the candle host are allowed`() {
        assertTrue(isCandleResourceUrl("https://emmunioo.github.io/muru/"))
        assertTrue(isCandleResourceUrl("https://emmunioo.github.io/muru/style.css"))
        assertFalse(isCandleResourceUrl("http://emmunioo.github.io/muru/style.css"))
        assertFalse(isCandleResourceUrl("https://fonts.googleapis.com/css2?family=Nunito+Sans"))
        assertFalse(isCandleResourceUrl("https://fonts.gstatic.com/s/font.woff2"))
        assertFalse(isCandleResourceUrl("https://emmunioo.github.io.evil.example/x.js"))
        assertFalse(isCandleResourceUrl("data:text/html,x"))
    }

    @Test
    fun `page navigation stays under the candle address`() {
        assertTrue(isCandlePageUrl("https://emmunioo.github.io/muru/index.html"))
        assertFalse(isCandlePageUrl("https://emmunioo.github.io/inne/"))
    }
}
