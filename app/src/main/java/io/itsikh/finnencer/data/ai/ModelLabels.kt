package io.itsikh.finnencer.data.ai

/**
 * Map a model id (Anthropic / Gemini wire id, or a runtime-discovered
 * Gemini id) to a short human label suitable for an attribution line
 * like "via Claude Sonnet 4.6".
 *
 * Lookup order:
 *  1. Built-in [AiModel] catalog → use the curated [AiModel.displayName].
 *  2. Heuristic strip of the date suffix on Anthropic ids
 *     (`claude-haiku-4-5-20251001` → `Claude Haiku 4.5`).
 *  3. Fall back to the raw id — better an ugly id than a misleading guess.
 *
 * Returns null only when [id] is null/blank.
 */
fun friendlyModelLabel(id: String?): String? {
    if (id.isNullOrBlank()) return null
    AiModel.byId(id)?.let { return it.displayName }
    if (id.startsWith("claude-")) {
        // Drop trailing `-YYYYMMDD` snapshot date if present and prettify.
        val core = id.removePrefix("claude-").replace(Regex("-\\d{8}$"), "")
        // tokens like "haiku-4-5" → "Haiku 4.5"; "sonnet-4-6" → "Sonnet 4.6"
        val parts = core.split("-")
        val family = parts.firstOrNull()?.replaceFirstChar { it.uppercase() } ?: return id
        val version = parts.drop(1).joinToString(".")
        return if (version.isBlank()) "Claude $family" else "Claude $family $version"
    }
    if (id.startsWith("gemini-")) {
        val tail = id.removePrefix("gemini-")
        return "Gemini " + tail.split("-").joinToString(" ") { token ->
            if (token.first().isDigit()) token.replace(Regex("(\\d)(\\d)"), "$1.$2")
            else token.replaceFirstChar { it.uppercase() }
        }
    }
    return id
}
