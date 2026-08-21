package pl.lewicowyt.notifier.search

import java.util.ArrayDeque
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import pl.lewicowyt.notifier.network.hasSafeJsonNesting

internal const val MAX_OLDER_SEARCH_JSON_DEPTH = 64
internal const val MAX_OLDER_SEARCH_JSON_NODES = 50_000
internal const val MAX_OLDER_SEARCH_CONTAINER_ITEMS = 2_048
internal const val MAX_OLDER_SEARCH_CANDIDATES = 100
internal const val MAX_OLDER_SEARCH_JSON_CHARS = 4_000_000

internal data class OlderMaterialJsonLimits(
    val maxDepth: Int = MAX_OLDER_SEARCH_JSON_DEPTH,
    val maxNodes: Int = MAX_OLDER_SEARCH_JSON_NODES,
    val maxContainerItems: Int = MAX_OLDER_SEARCH_CONTAINER_ITEMS,
    val maxCandidates: Int = MAX_OLDER_SEARCH_CANDIDATES,
    val maxJsonChars: Int = MAX_OLDER_SEARCH_JSON_CHARS,
) {
    init {
        require(maxDepth > 0)
        require(maxNodes > 0)
        require(maxContainerItems > 0)
        require(maxCandidates > 0)
        require(maxJsonChars > 0)
    }
}

internal data class ParsedOlderMaterialCandidate(
    val videoId: String,
    val title: String,
    val searchEvidence: String?,
)

internal class OlderMaterialSearchResponseException(message: String) :
    IllegalStateException(message)

private data class NodeFrame(val value: Any, val depth: Int)

internal fun parseOlderMaterialInitialData(
    rawJson: String,
    candidateLimit: Int = MAX_OLDER_SEARCH_CANDIDATES,
    limits: OlderMaterialJsonLimits = OlderMaterialJsonLimits(),
): List<ParsedOlderMaterialCandidate> {
    if (rawJson.length > limits.maxJsonChars) {
        throw OlderMaterialSearchResponseException("Odpowiedź wyszukiwania jest zbyt duża.")
    }
    if (!hasSafeJsonNesting(rawJson, limits.maxDepth)) {
        throw OlderMaterialSearchResponseException(
            "Odpowiedź wyszukiwania ma niebezpieczną lub uszkodzoną strukturę JSON.",
        )
    }
    val root = try {
        JSONObject(rawJson)
    } catch (_: JSONException) {
        throw OlderMaterialSearchResponseException("YouTube zwrócił nieprawidłowe dane wyszukiwania.")
    }
    val effectiveLimit = candidateLimit.coerceIn(1, limits.maxCandidates)
    val candidates = linkedMapOf<String, ParsedOlderMaterialCandidate>()
    walkJsonBounded(root, limits) { value ->
        for (key in VIDEO_RENDERER_KEYS) {
            val renderer = value.optJSONObject(key) ?: continue
            requireContainerWithinLimit(renderer.length(), limits)
            val videoId = renderer.optString("videoId").takeIf(VIDEO_ID::matches) ?: continue
            val title = readText(renderer.opt("title"), limits)
                ?: readText(renderer.opt("headline"), limits)
                ?: renderer.optJSONObject("overlayMetadata")
                    ?.optJSONObject("primaryText")
                    ?.optString("content")
            val safeTitle = title?.trim()?.takeIf(String::isNotBlank)
                ?.take(MAX_TITLE_CHARS)
                ?: continue
            val evidence = readDescriptionEvidence(renderer, limits)
            val previous = candidates[videoId]
            candidates[videoId] = ParsedOlderMaterialCandidate(
                videoId = videoId,
                title = previous?.title ?: safeTitle,
                searchEvidence = sequenceOf(previous?.searchEvidence, evidence)
                    .filterNotNull()
                    .joinToString(" ")
                    .take(MAX_SEARCH_EVIDENCE_CHARS)
                    .takeIf(String::isNotBlank),
            )
            if (candidates.size >= effectiveLimit) return@walkJsonBounded true
        }
        false
    }
    return candidates.values.toList()
}

/** Iteracyjny DFS: dane wejściowe nigdy nie sterują stosem JVM. */
private fun walkJsonBounded(
    root: JSONObject,
    limits: OlderMaterialJsonLimits,
    visitor: (JSONObject) -> Boolean,
) {
    val pending = ArrayDeque<NodeFrame>()
    pending.addLast(NodeFrame(root, depth = 1))
    var visitedNodes = 0
    while (pending.isNotEmpty()) {
        val frame = pending.removeLast()
        if (frame.depth > limits.maxDepth) {
            throw OlderMaterialSearchResponseException("Odpowiedź wyszukiwania jest zbyt głęboka.")
        }
        visitedNodes += 1
        if (visitedNodes > limits.maxNodes) {
            throw OlderMaterialSearchResponseException("Odpowiedź wyszukiwania jest zbyt złożona.")
        }
        when (val value = frame.value) {
            is JSONObject -> {
                requireContainerWithinLimit(value.length(), limits)
                if (visitor(value)) return
                val children = mutableListOf<Any>()
                val keys = value.keys()
                while (keys.hasNext()) {
                    value.opt(keys.next())
                        ?.takeUnless { it === JSONObject.NULL }
                        ?.let(children::add)
                }
                reserveNodeBudget(visitedNodes, pending.size, children.size, limits)
                for (index in children.indices.reversed()) {
                    pending.addLast(NodeFrame(children[index], frame.depth + 1))
                }
            }
            is JSONArray -> {
                requireContainerWithinLimit(value.length(), limits)
                reserveNodeBudget(visitedNodes, pending.size, value.length(), limits)
                for (index in value.length() - 1 downTo 0) {
                    value.opt(index)
                        ?.takeUnless { it === JSONObject.NULL }
                        ?.let { pending.addLast(NodeFrame(it, frame.depth + 1)) }
                }
            }
        }
    }
}

private fun reserveNodeBudget(
    visitedNodes: Int,
    pendingNodes: Int,
    newNodes: Int,
    limits: OlderMaterialJsonLimits,
) {
    if (visitedNodes.toLong() + pendingNodes + newNodes > limits.maxNodes.toLong()) {
        throw OlderMaterialSearchResponseException("Odpowiedź wyszukiwania jest zbyt złożona.")
    }
}

private fun requireContainerWithinLimit(size: Int, limits: OlderMaterialJsonLimits) {
    if (size > limits.maxContainerItems) {
        throw OlderMaterialSearchResponseException("Odpowiedź wyszukiwania zawiera zbyt szeroką listę.")
    }
}

private fun readDescriptionEvidence(
    renderer: JSONObject,
    limits: OlderMaterialJsonLimits,
): String? = buildList {
    readText(renderer.opt("descriptionSnippet"), limits)?.let(::add)
    renderer.optJSONArray("detailedMetadataSnippets")?.let { snippets ->
        requireContainerWithinLimit(snippets.length(), limits)
        for (index in 0 until snippets.length()) {
            val snippet = snippets.optJSONObject(index) ?: continue
            requireContainerWithinLimit(snippet.length(), limits)
            readText(snippet.opt("snippetText"), limits)?.let(::add)
        }
    }
}.joinToString(" ")
    .replace(WHITESPACE, " ")
    .trim()
    .takeIf(String::isNotBlank)
    ?.take(MAX_SEARCH_EVIDENCE_CHARS)

private fun readText(value: Any?, limits: OlderMaterialJsonLimits): String? = when (value) {
    is String -> value
    is JSONObject -> {
        requireContainerWithinLimit(value.length(), limits)
        value.optString("content").takeIf(String::isNotBlank)
            ?: value.optString("simpleText").takeIf(String::isNotBlank)
            ?: value.optJSONArray("runs")?.let { runs ->
                requireContainerWithinLimit(runs.length(), limits)
                buildString {
                    for (index in 0 until runs.length()) {
                        append(runs.optJSONObject(index)?.optString("text").orEmpty())
                    }
                }.takeIf(String::isNotBlank)
            }
    }
    else -> null
}

internal fun extractOlderMaterialInitialData(html: String): String? {
    for (marker in INITIAL_DATA_MARKERS) {
        val markerIndex = html.indexOf(marker)
        if (markerIndex < 0) continue
        return extractJsonObjectAfterMarker(html, markerIndex + marker.length)
            ?: throw OlderMaterialSearchResponseException(
                "YouTube zwrócił niepełne dane wyszukiwania.",
            )
    }
    return null
}

private fun extractJsonObjectAfterMarker(source: String, fromIndex: Int): String? {
    val start = source.indexOf('{', fromIndex)
    if (start < 0) return null
    var depth = 0
    var inString = false
    var escaped = false
    for (index in start until source.length) {
        val character = source[index]
        if (inString) {
            when {
                escaped -> escaped = false
                character == '\\' -> escaped = true
                character == '"' -> inString = false
            }
        } else {
            when (character) {
                '"' -> inString = true
                '{' -> depth += 1
                '}' -> if (--depth == 0) return source.substring(start, index + 1)
            }
        }
    }
    return null
}

private const val MAX_TITLE_CHARS = 300
private const val MAX_SEARCH_EVIDENCE_CHARS = 4_000
private val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")
private val WHITESPACE = Regex("\\s+")
private val VIDEO_RENDERER_KEYS = listOf(
    "videoRenderer",
    "gridVideoRenderer",
    "reelItemRenderer",
)
private val INITIAL_DATA_MARKERS = listOf(
    "var ytInitialData =",
    "window[\"ytInitialData\"] =",
    "ytInitialData =",
    "\"ytInitialData\":",
)
