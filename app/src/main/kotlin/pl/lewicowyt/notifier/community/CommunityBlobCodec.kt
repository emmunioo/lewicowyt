package pl.lewicowyt.notifier.community

import com.github.luben.zstd.Zstd
import java.nio.charset.StandardCharsets

/**
 * Kompresja tekstu treści społeczności do wspólnej bazy. Cały tekst postów/artykułów itd.
 * jest tu ściskany ZSTD-em (ten sam kodek co opisy filmów), żeby magazyn zajmował mało miejsca.
 * Gdy kompresja nic nie daje (króciutki tekst), zostaje surowy UTF-8 — dekoder rozpoznaje to
 * po znaczniku kodeka.
 */
internal enum class CommunityBlobCodec(val id: Int) {
    UTF8(1),
    ZSTD(2),
}

internal data class CommunityEncodedBlob(
    val data: ByteArray,
    val codec: Int,
    val originalSize: Int,
)

internal object CommunityBlob {
    private const val LEVEL = 5
    private const val MAX_BYTES = 2_000_000
    private const val MIN_NET_SAVING_BYTES = 16

    fun encode(value: String): CommunityEncodedBlob {
        val raw = value.toByteArray(StandardCharsets.UTF_8)
        require(raw.size <= MAX_BYTES) { "Treść przekracza bezpieczny limit magazynu" }
        val compressed = runCatching { Zstd.compress(raw, LEVEL) }.getOrNull()
        return if (compressed != null && compressed.size + MIN_NET_SAVING_BYTES < raw.size) {
            CommunityEncodedBlob(compressed, CommunityBlobCodec.ZSTD.id, raw.size)
        } else {
            CommunityEncodedBlob(raw, CommunityBlobCodec.UTF8.id, raw.size)
        }
    }

    fun decode(data: ByteArray?, codecId: Int, originalSize: Int): String? {
        if (data == null || data.isEmpty()) return null
        if (originalSize !in 0..MAX_BYTES) return null
        val bytes = when (codecId) {
            CommunityBlobCodec.UTF8.id -> data
            CommunityBlobCodec.ZSTD.id -> {
                if (originalSize <= 0) return null
                runCatching {
                    val target = ByteArray(originalSize)
                    val written = Zstd.decompress(target, data)
                    if (Zstd.isError(written) || written != originalSize.toLong()) null else target
                }.getOrNull() ?: return null
            }
            else -> return null
        }
        return runCatching { String(bytes, StandardCharsets.UTF_8) }.getOrNull()
    }
}
