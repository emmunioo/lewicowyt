package pl.lewicowyt.notifier.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DescriptionCodecInstrumentedTest {
    @Test
    fun repetitiveDescriptionUsesZstdAndRoundTrips() {
        val source = "Opis materiału o sprawiedliwości społecznej. ".repeat(200)

        val encoded = DescriptionCodec.encode(source)

        assertEquals(StoredDescriptionCodec.ZSTD_5, encoded.codec)
        assertTrue(encoded.data.size < source.toByteArray().size)
        assertEquals(source.trim(), DescriptionCodec.decode(
            encoded.data,
            encoded.codec.databaseValue,
            encoded.originalSize,
        ))
    }

    @Test
    fun shortDescriptionFallsBackToUtf8WhenCompressionDoesNotSaveSpace() {
        val source = "Krótki opis"

        val encoded = DescriptionCodec.encode(source)

        assertEquals(StoredDescriptionCodec.UTF8, encoded.codec)
        assertEquals(source, DescriptionCodec.decode(
            encoded.data,
            encoded.codec.databaseValue,
            encoded.originalSize,
        ))
    }

    @Test
    fun invalidMetadataIsRejectedInsteadOfAllocatingUnboundedBuffer() {
        assertNull(
            DescriptionCodec.decode(
                data = byteArrayOf(1, 2, 3),
                codecValue = StoredDescriptionCodec.ZSTD_5.databaseValue,
                originalSize = Int.MAX_VALUE,
            ),
        )
    }
}
