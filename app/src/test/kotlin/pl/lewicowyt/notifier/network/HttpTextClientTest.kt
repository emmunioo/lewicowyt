package pl.lewicowyt.notifier.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpTextClientTest {
    @Test
    fun metadataClientAcceptsOnlyTextJsonAndXmlResponses() {
        assertTrue(isAllowedTextResponseMime("application/json; charset=UTF-8"))
        assertTrue(isAllowedTextResponseMime("application/atom+xml"))
        assertTrue(isAllowedTextResponseMime("text/html"))
        assertFalse(isAllowedTextResponseMime("image/jpeg"))
        assertFalse(isAllowedTextResponseMime("image/webp"))
        assertFalse(isAllowedTextResponseMime("application/octet-stream"))
    }
}
