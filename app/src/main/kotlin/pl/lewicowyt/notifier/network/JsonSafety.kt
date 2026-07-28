package pl.lewicowyt.notifier.network

/**
 * Odrzuca nadmiernie zagnieżdżony albo niezbilansowany JSON przed przekazaniem
 * go parserowi. Nawiasy znajdujące się wewnątrz napisów są ignorowane.
 */
internal fun hasSafeJsonNesting(
    json: String,
    maxDepth: Int = MAX_JSON_DEPTH,
): Boolean {
    var depth = 0
    var inString = false
    var escaped = false
    val stack = CharArray(maxDepth.coerceAtLeast(0))
    for (character in json) {
        if (inString) {
            when {
                escaped -> escaped = false
                character == '\\' -> escaped = true
                character == '"' -> inString = false
            }
            continue
        }
        when (character) {
            '"' -> inString = true
            '{', '[' -> {
                if (depth >= stack.size) return false
                stack[depth] = character
                depth += 1
            }
            '}', ']' -> {
                if (depth == 0) return false
                val opening = stack[depth - 1]
                if (
                    (character == '}' && opening != '{') ||
                    (character == ']' && opening != '[')
                ) {
                    return false
                }
                depth -= 1
            }
        }
    }
    return !inString && depth == 0
}

private const val MAX_JSON_DEPTH = 100
