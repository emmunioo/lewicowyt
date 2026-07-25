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
        val array = JSONArray(json)
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val sourcesJson = item.getJSONArray("sources")
                val sources = buildList {
                    for (sourceIndex in 0 until sourcesJson.length()) {
                        val source = sourcesJson.getJSONObject(sourceIndex)
                        add(
                            CreatorSource(
                                type = SourceType.valueOf(source.getString("type")),
                                url = source.getString("url"),
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
                    ),
                )
            }
        }.sortedBy { it.name.lowercase(Locale.forLanguageTag("pl")) }
    }

    fun findById(id: String): Creator? = creators.firstOrNull { it.id == id }
}
