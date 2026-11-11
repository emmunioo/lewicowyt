package pl.lewicowyt.notifier.data

import android.content.Context
import org.json.JSONArray
import pl.lewicowyt.notifier.model.Creator
import pl.lewicowyt.notifier.model.CreatorSource
import pl.lewicowyt.notifier.model.SourceType
import java.util.Locale

class CreatorCatalog(private val context: Context) {
    val creators: List<Creator> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        loadCreators()
    }

    private fun loadCreators(): List<Creator> {
        val json = context.assets.open("creators.json")
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
        return parseCatalog(json)
    }

    fun findById(id: String): Creator? = creators.firstOrNull { it.id == id }

    companion object {
        /**
         * parsuje katalog twórców tolerancyjnie. katalog może kiedyś dostać źródła
         * platform, których ta wersja jeszcze nie ogarnia (np. TWITCH/TIKTOK).
         * zamiast wywalać ładowanie całego katalogu na pierwszym nieznanym typie
         * (`SourceType.valueOf` rzucał wyjątkiem), pomijamy pojedyncze nieznane/zepsute
         * źródła i wpisy bez wymaganych pól – reszta katalogu działa. „nieznane = ignoruj, nie błąd".
         */
        internal fun parseCatalog(json: String): List<Creator> {
            val array = JSONArray(json)
            return buildList {
                for (index in 0 until array.length()) {
                    runCatching {
                        val item = array.getJSONObject(index)
                        val sourcesJson = item.optJSONArray("sources") ?: JSONArray()
                        val sources = buildList {
                            for (sourceIndex in 0 until sourcesJson.length()) {
                                val source = sourcesJson.optJSONObject(sourceIndex) ?: continue
                                // nieznany/nieobsługiwany typ źródła → pomijamy je
                                val type = parseSourceType(source.optString("type")) ?: continue
                                add(
                                    CreatorSource(
                                        type = type,
                                        url = source.optString("url"),
                                        externalId = if (source.isNull("externalId")) {
                                            null
                                        } else {
                                            source.optString("externalId").ifBlank { null }
                                        },
                                    ),
                                )
                            }
                        }
                        add(
                            Creator(
                                id = item.getString("id"),
                                name = item.getString("name"),
                                sources = sources,
                                profileChannelId = item.optString("profileChannelId")
                                    .ifBlank { null },
                            ),
                        )
                    }
                }
            }.sortedBy { it.name.lowercase(Locale.forLanguageTag("pl")) }
        }

        /** znany typ źródła albo null (nieznana/przyszła platforma) */
        private fun parseSourceType(raw: String?): SourceType? {
            val name = raw?.trim().orEmpty()
            return SourceType.values().firstOrNull { it.name.equals(name, ignoreCase = true) }
        }
    }
}
