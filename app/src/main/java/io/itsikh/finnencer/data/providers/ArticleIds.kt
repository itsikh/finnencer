package io.itsikh.finnencer.data.providers

import java.security.MessageDigest

object ArticleIds {

    /**
     * Stable, opaque article id derived from provider + source identifier.
     * Re-ingesting the same article from the same provider produces the same
     * id, so Room's `OnConflictStrategy.IGNORE` makes ingestion idempotent.
     */
    fun stableId(provider: String, sourceArticleId: String): String =
        sha256("$provider::$sourceArticleId").take(32)

    /**
     * Cluster key: a tokenized, normalized hash of the headline. The same
     * story carried by 5 different wires within a few hours produces the same
     * cluster key, so the notification engine can dedupe.
     *
     * Steps:
     *  - lower
     *  - drop quotes/brackets/punctuation
     *  - drop digits and currency symbols
     *  - split on whitespace
     *  - drop English stopwords
     *  - take first 8 surviving tokens
     *  - hash
     */
    fun clusterKey(title: String): String {
        if (title.isBlank()) return "empty"
        val sanitized = title.lowercase()
            .replace(Regex("[\\p{Punct}$€£¥]"), " ")
            .replace(Regex("\\d+"), " ")
        val tokens = sanitized.split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .filter { it !in STOPWORDS }
            .take(8)
        if (tokens.isEmpty()) return sha256(title).take(16)
        return sha256(tokens.joinToString(" ")).take(16)
    }

    private fun sha256(s: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(s.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(String.format("%02x", b.toInt() and 0xff))
        }
        return sb.toString()
    }

    private val STOPWORDS = setOf(
        "a", "an", "the", "and", "or", "but", "to", "of", "in", "on", "at",
        "for", "with", "by", "from", "as", "is", "are", "was", "were", "be",
        "been", "being", "has", "have", "had", "do", "does", "did", "this",
        "that", "these", "those", "it", "its", "after", "before", "over",
        "under", "into", "out", "up", "down", "amid", "amidst", "says", "said",
        "will", "would", "could", "should", "shall", "may", "might",
    )
}
