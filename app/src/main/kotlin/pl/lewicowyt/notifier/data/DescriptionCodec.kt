package pl.lewicowyt.notifier.data

import com.github.luben.zstd.Zstd
import java.nio.charset.StandardCharsets

internal enum class StoredDescriptionCodec(val databaseValue: Int) {
    NONE(0),
    UTF8(1),
    ZSTD_5(2),
}

internal data class EncodedDescription(
    val data: ByteArray,
    val codec: StoredDescriptionCodec,
    val originalSize: Int,
    val dictionaryId: String? = null,
    val dictionaryVersion: Int? = null,
)

/**
 * Pełny opis pozostaje poza FTS5. Indeks otrzymuje osobną, oczyszczoną wersję
 * tekstową, a ten kodek służy wyłącznie do oszczędnego magazynu BLOB.
 */
internal object DescriptionCodec {
    private const val LEVEL = 5
    private const val MAX_DESCRIPTION_BYTES = 1_000_000
    private const val MIN_NET_SAVING_BYTES = 16

    fun encode(value: String): EncodedDescription {
        val normalized = value.trim().take(MAX_DESCRIPTION_CHARS)
        val raw = normalized.toByteArray(StandardCharsets.UTF_8)
        require(raw.size <= MAX_DESCRIPTION_BYTES) { "Opis przekracza bezpieczny limit" }
        val compressed = Zstd.compress(raw, LEVEL)
        return if (compressed.size + MIN_NET_SAVING_BYTES < raw.size) {
            EncodedDescription(compressed, StoredDescriptionCodec.ZSTD_5, raw.size)
        } else {
            EncodedDescription(raw, StoredDescriptionCodec.UTF8, raw.size)
        }
    }

    fun decode(
        data: ByteArray?,
        codecValue: Int,
        originalSize: Int,
    ): String? {
        if (data == null || data.isEmpty()) return null
        if (originalSize !in 0..MAX_DESCRIPTION_BYTES) return null
        val decoded = when (codecValue) {
            StoredDescriptionCodec.UTF8.databaseValue -> data
            StoredDescriptionCodec.ZSTD_5.databaseValue -> {
                if (originalSize <= 0) return null
                runCatching {
                    val target = ByteArray(originalSize)
                    val written = Zstd.decompress(target, data)
                    if (Zstd.isError(written) || written != originalSize.toLong()) null
                    else target
                }.getOrNull() ?: return null
            }
            else -> return null
        }
        return runCatching {
            String(decoded, StandardCharsets.UTF_8).take(MAX_DESCRIPTION_CHARS)
        }.getOrNull()
    }

    fun searchableText(value: String): String = value
        .replace('\u0000', ' ')
        .replace(HTML_TAG, " ")
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace(WHITESPACE, " ")
        .trim()
        .take(MAX_SEARCHABLE_DESCRIPTION_CHARS)

    // Kompilowane raz. Wcześniej te dwa wzorce powstawały na nowo przy każdym
    // wywołaniu searchableText (raz na indeksowany opis) (#10).
    private val HTML_TAG = Regex("<[^>]{1,500}>")
    private val WHITESPACE = Regex("\\s+")

    private const val MAX_DESCRIPTION_CHARS = 200_000
    private const val MAX_SEARCHABLE_DESCRIPTION_CHARS = 20_000
}
