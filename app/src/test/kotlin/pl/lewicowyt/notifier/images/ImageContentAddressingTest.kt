package pl.lewicowyt.notifier.images

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageContentAddressingTest {
    @Test
    fun identicalThumbnailBytesUseOneContentKeyForDifferentUrls() {
        val thumbnail = "identyczna miniatura Ralindela".toByteArray()
        val firstUrl = "https://i.ytimg.com/vi/aaaaaaaaaaa/sddefault.jpg"
        val secondUrl = "https://i.ytimg.com/vi/bbbbbbbbbbb/sddefault.jpg"
        val directory = Files.createTempDirectory("lewicowyt-content-files").toFile()
        val firstContentKey = ImageContentAddressing.contentKey(thumbnail)
        val secondContentKey = ImageContentAddressing.contentKey(thumbnail.copyOf())

        try {
            assertNotEquals(
                ImageContentAddressing.urlKey(firstUrl),
                ImageContentAddressing.urlKey(secondUrl),
            )
            assertEquals(firstContentKey, secondContentKey)
            assertEquals(
                ImageContentAddressing.contentFile(directory, firstContentKey, "jxl"),
                ImageContentAddressing.contentFile(directory, secondContentKey, "jxl"),
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun differentThumbnailBytesDoNotCollide() {
        assertNotEquals(
            ImageContentAddressing.contentKey(byteArrayOf(1, 2, 3)),
            ImageContentAddressing.contentKey(byteArrayOf(1, 2, 4)),
        )
    }

    @Test
    fun referencesAcceptOnlyCompleteSha256Values() {
        val directory = Files.createTempDirectory("lewicowyt-image-addressing").toFile()
        try {
            val validKey = ImageContentAddressing.contentKey("obraz".toByteArray())
            val valid = directory.resolve("valid.ref").apply { writeText(validKey.uppercase()) }
            val invalid = directory.resolve("invalid.ref").apply { writeText("../poza-cache") }

            assertEquals(validKey, ImageContentAddressing.readReference(valid))
            assertNull(ImageContentAddressing.readReference(invalid))
            assertEquals(setOf(validKey), ImageContentAddressing.referencedContentKeys(directory))
            assertTrue(ImageContentAddressing.isValidKey(validKey))
            assertFalse(ImageContentAddressing.isValidKey(validKey.dropLast(1)))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun sharedContentRemainsReferencedUntilLastUrlReferenceDisappears() {
        val directory = Files.createTempDirectory("lewicowyt-shared-image").toFile()
        try {
            val contentKey = ImageContentAddressing.contentKey("wspólny obraz".toByteArray())
            val firstReference = directory.resolve("first.ref").apply { writeText(contentKey) }
            val secondReference = directory.resolve("second.ref").apply { writeText(contentKey) }

            firstReference.delete()
            assertEquals(
                setOf(contentKey),
                ImageContentAddressing.referencedContentKeys(directory),
            )

            secondReference.delete()
            assertTrue(ImageContentAddressing.referencedContentKeys(directory).isEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }
}
