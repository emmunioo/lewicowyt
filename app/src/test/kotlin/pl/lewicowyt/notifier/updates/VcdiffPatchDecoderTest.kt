package pl.lewicowyt.notifier.updates

import java.io.File
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VcdiffPatchDecoderTest {
    @Test
    fun decodesPatchGeneratedByPinnedXdelta3() {
        val directory = createTempDirectory(prefix = "lewicowyt-vcdiff-").toFile()
        try {
            val source = copyResource("vcdiff/source.bin", File(directory, "source.bin"))
            val patch = copyResource("vcdiff/update.xdelta", File(directory, "update.xdelta"))
            val expected = javaClass.classLoader!!.getResourceAsStream("vcdiff/target.bin")!!.readBytes()
            val output = File(directory, "target.bin")
            StreamingVcdiffPatchDecoder().apply(source, patch, output) { }
            assertArrayEquals(expected, output.readBytes())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun wrongSourceNeverProducesExpectedTarget() {
        val directory = createTempDirectory(prefix = "lewicowyt-vcdiff-wrong-source-").toFile()
        try {
            val source = copyResource("vcdiff/source.bin", File(directory, "wrong-source.bin"))
            val sourceBytes = source.readBytes()
            sourceBytes.indices.forEach { index ->
                sourceBytes[index] = (sourceBytes[index].toInt() xor 0x5a).toByte()
            }
            source.writeBytes(sourceBytes)
            val patch = copyResource("vcdiff/update.xdelta", File(directory, "update.xdelta"))
            val expected = javaClass.classLoader!!.getResourceAsStream("vcdiff/target.bin")!!.readBytes()
            val output = File(directory, "target.bin")

            val result = runCatching {
                StreamingVcdiffPatchDecoder().apply(source, patch, output) { }
            }

            if (result.isSuccess) {
                assertFalse(MessageDigest.isEqual(sha256(expected), sha256(output.readBytes())))
            } else {
                assertFalse(output.exists())
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun malformedPatchFailsAndRemovesPartialTarget() {
        val directory = createTempDirectory(prefix = "lewicowyt-vcdiff-corrupt-").toFile()
        try {
            val source = copyResource("vcdiff/source.bin", File(directory, "source.bin"))
            val patch = copyResource("vcdiff/update.xdelta", File(directory, "update.xdelta"))
            val bytes = patch.readBytes()
            bytes[0] = (bytes[0].toInt() xor 0x7f).toByte()
            patch.writeBytes(bytes)
            val output = File(directory, "target.bin")

            assertTrue(
                runCatching {
                    StreamingVcdiffPatchDecoder().apply(source, patch, output) { }
                }.isFailure,
            )
            assertFalse(output.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun copyResource(name: String, target: File): File {
        javaClass.classLoader!!.getResourceAsStream(name)!!.use { input ->
            target.outputStream().use(input::copyTo)
        }
        return target
    }

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)
}
