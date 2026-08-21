package pl.lewicowyt.notifier.updates

import com.davidehrmann.vcdiff.VCDiffDecoderBuilder
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

internal fun interface PatchCancellationCheck {
    fun ensureActive()
}

internal interface VcdiffPatchDecoder {
    @Throws(IOException::class)
    fun apply(
        sourceApk: File,
        patch: File,
        targetApk: File,
        cancellationCheck: PatchCancellationCheck,
    )
}

/**
 * Czysto javowy dekoder RFC 3284. Zainstalowany APK jest mapowany tylko do
 * odczytu z pliku, a poprawka i wynik są przetwarzane strumieniowo. Generator
 * tworzy bazowy VCDIFF bez secondary compression i application header. Używany
 * encoder Xdelta3 nie emituje VCD_TARGET, więc dekoder nie przechowuje
 * poprzednich okien wyniku.
 */
internal class StreamingVcdiffPatchDecoder : VcdiffPatchDecoder {
    override fun apply(
        sourceApk: File,
        patch: File,
        targetApk: File,
        cancellationCheck: PatchCancellationCheck,
    ) {
        require(sourceApk.isFile) { "Brak źródłowego APK." }
        require(patch.isFile) { "Brak poprawki VCDIFF." }
        if (sourceApk.length() !in 1..MAX_RECONSTRUCTED_APK_BYTES) {
            throw IOException("Źródłowy APK ma niedozwolony rozmiar.")
        }
        if (patch.length() !in 1..MAX_DELTA_PATCH_BYTES) {
            throw IOException("Poprawka VCDIFF ma niedozwolony rozmiar.")
        }

        targetApk.parentFile?.mkdirs()
        targetApk.delete()
        try {
            FileInputStream(sourceApk).channel.use { sourceChannel ->
                val dictionary = sourceChannel.map(
                    FileChannel.MapMode.READ_ONLY,
                    0,
                    sourceChannel.size(),
                )
                val decoder = VCDiffDecoderBuilder.builder()
                    .withMaxTargetFileSize(MAX_RECONSTRUCTED_APK_BYTES)
                    .withMaxTargetWindowSize(MAX_VCDIFF_WINDOW_BYTES)
                    .withAllowTargetMatches(false)
                    .buildStreaming()
                decoder.startDecoding(dictionary)
                BufferedInputStream(FileInputStream(patch), IO_BUFFER_BYTES).use { input ->
                    FileOutputStream(targetApk).use { fileOutput ->
                        BufferedOutputStream(fileOutput, IO_BUFFER_BYTES).use { output ->
                            val buffer = ByteArray(IO_BUFFER_BYTES)
                            while (true) {
                                cancellationCheck.ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                decoder.decodeChunk(ByteBuffer.wrap(buffer, 0, count), output)
                            }
                            decoder.finishDecoding()
                            output.flush()
                            cancellationCheck.ensureActive()
                            fileOutput.fd.sync()
                        }
                    }
                }
            }
            if (targetApk.length() !in 1..MAX_RECONSTRUCTED_APK_BYTES) {
                throw IOException("Odtworzony APK ma niedozwolony rozmiar.")
            }
        } catch (error: Exception) {
            targetApk.delete()
            throw error
        }
    }

    private companion object {
        const val IO_BUFFER_BYTES = 64 * 1024
        const val MAX_VCDIFF_WINDOW_BYTES = 8 * 1024 * 1024
    }
}
