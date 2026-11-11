package pl.lewicowyt.notifier.community

import org.json.JSONArray
import org.json.JSONObject

/**
 * R-01: (de)serializacja konfiguracji `push` i `primaryFeed` serwera do/z JSON przechowywanego w
 * community_servers. Dzięki temu po zimnym starcie (albo gdy serwer odpowie 304) [CommunityRepository.
 * restoredState] odtwarza manifest Z `push`/`primaryFeed`, więc serwer nie wypada z rejestru push,
 * a jego budziki nadal da się przypisać. Czysta logika — bez bazy i sieci.
 */
object CommunityServerCodec {

    fun pushToJson(push: CommunityPushConfig?): String? = push?.let { p ->
        JSONObject().apply {
            put("r", p.registerUrl)
            p.unregisterUrl?.let { put("u", it) }
            if (p.requiresNonPrivateTor) put("nt", true)
            p.relayBase?.let { put("rl", it) }
            p.fcm?.let {
                put(
                    "fcm",
                    JSONObject()
                        .put("projectId", it.projectId)
                        .put("appId", it.appId)
                        .put("apiKey", it.apiKey)
                        .put("senderId", it.senderId),
                )
            }
        }.toString()
    }

    fun pushFromJson(json: String?): CommunityPushConfig? {
        val o = runCatching { JSONObject(json ?: return null) }.getOrNull() ?: return null
        val register = o.optString("r").ifBlank { null } ?: return null
        val fcm = o.optJSONObject("fcm")?.let {
            val projectId = it.optString("projectId")
            val appId = it.optString("appId")
            val apiKey = it.optString("apiKey")
            val senderId = it.optString("senderId")
            if (projectId.isBlank() || appId.isBlank() || apiKey.isBlank() || senderId.isBlank()) null
            else CommunityFcmConfig(projectId, appId, apiKey, senderId)
        }
        return CommunityPushConfig(
            register,
            o.optString("u").ifBlank { null },
            fcm,
            o.optBoolean("nt", false),
            o.optString("rl").ifBlank { null },
        )
    }

    fun primaryFeedToJson(feed: CommunityPrimaryFeed?): String? = feed?.let { f ->
        JSONObject().apply {
            put("creators", JSONArray(f.creators))
            put(
                "videos",
                JSONArray().apply {
                    f.videos.forEach { v ->
                        put(
                            JSONObject()
                                .put("channelId", v.channelId)
                                .put("videoId", v.videoId)
                                .apply { v.publishedAtMillis?.let { put("publishedAtMillis", it) } },
                        )
                    }
                },
            )
        }.toString()
    }

    fun primaryFeedFromJson(json: String?): CommunityPrimaryFeed? {
        val o = runCatching { JSONObject(json ?: return null) }.getOrNull() ?: return null
        val creators = o.optJSONArray("creators")?.let { a ->
            buildList { for (i in 0 until a.length()) a.optString(i).ifBlank { null }?.let { add(it) } }
        }.orEmpty()
        val videos = o.optJSONArray("videos")?.let { a ->
            buildList {
                for (i in 0 until a.length()) {
                    val v = a.optJSONObject(i) ?: continue
                    val ch = v.optString("channelId").ifBlank { null } ?: continue
                    val vid = v.optString("videoId").ifBlank { null } ?: continue
                    add(CommunityPrimaryVideo(ch, vid, if (v.has("publishedAtMillis")) v.optLong("publishedAtMillis") else null))
                }
            }
        }.orEmpty()
        if (creators.isEmpty() && videos.isEmpty()) return null
        return CommunityPrimaryFeed(creators, videos)
    }
}
