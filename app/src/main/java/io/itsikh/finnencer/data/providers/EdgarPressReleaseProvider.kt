package io.itsikh.finnencer.data.providers

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.itsikh.finnencer.data.api.SecEdgarService
import io.itsikh.finnencer.logging.AppLogger
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The earnings press release itself — Exhibit 99.1 of the 8-K a company
 * files under Item 2.02 ("Results of Operations and Financial
 * Condition").
 *
 * This is where the two things XBRL cannot give us actually live:
 * **forward guidance** and **segment-level revenue**. Neither is
 * XBRL-tagged in a way the companyfacts API exposes, so without reading
 * the release itself an earnings report has no guidance section worth
 * the name.
 *
 * [text] is the de-tagged release with table cells joined by " | " so a
 * language model can read the segment tables as tables. We deliberately
 * hand the model the prose rather than regex-extracting segments
 * ourselves: pulling structured meaning out of wildly varying filer HTML
 * is exactly what the model is better at than we are. What we DO extract
 * mechanically is [guidanceSentences] — a highlighted subset — so the
 * guidance can't get lost in the middle of a 40-page release.
 */
data class EarningsPressRelease(
    val sourceUrl: String,
    val filedDate: LocalDate,
    /** 8-K item codes on the filing, e.g. "2.02,9.01". */
    val itemCodes: String?,
    /** De-tagged release text, capped at [EdgarPressReleaseProvider.MAX_TEXT_CHARS]. */
    val text: String,
    /** Sentences that look like forward guidance, in document order. */
    val guidanceSentences: List<String>,
) {
    val truncated: Boolean get() = text.length >= EdgarPressReleaseProvider.MAX_TEXT_CHARS
}

/**
 * Locates and extracts the earnings press release for a given report
 * date.
 *
 * Every step is best-effort and returns null rather than throwing —
 * filer HTML varies enormously, and a company that formats its release
 * unusually must degrade to "no press release section" rather than break
 * the whole earnings report.
 */
@Singleton
class EdgarPressReleaseProvider @Inject constructor(
    private val edgar: SecEdgarService,
) {

    private data class CacheEntry(val value: EarningsPressRelease?, val fetchedAt: Long)
    /** Keyed by "cik@reportDate" — a report regenerated at all three
     *  tiers shouldn't re-walk EDGAR three times. Caches negative
     *  results too: a filer with no parseable release won't suddenly
     *  gain one within the TTL. */
    private val cache = java.util.concurrent.ConcurrentHashMap<String, CacheEntry>()
    private data class SubmissionsEntry(val root: JsonObject, val fetchedAt: Long)
    private val submissionsCache = java.util.concurrent.ConcurrentHashMap<String, SubmissionsEntry>()

    /**
     * @param cik zero-padded 10-digit CIK
     * @param reportDate the date results were released; the 8-K normally
     *        lands the same day or the next business day
     */
    suspend fun forEarnings(cik: String, reportDate: LocalDate): EarningsPressRelease? {
        val cacheKey = "$cik@$reportDate"
        cache[cacheKey]?.let {
            if (System.currentTimeMillis() - it.fetchedAt < CACHE_TTL_MILLIS) return it.value
        }
        val result = runCatching { locate(cik, reportDate) }
            .onFailure { AppLogger.w(TAG, "press-release lookup failed for CIK $cik near $reportDate: ${it.message}") }
            .getOrNull()
        cache[cacheKey] = CacheEntry(result, System.currentTimeMillis())
        return result
    }

    private suspend fun locate(cik: String, reportDate: LocalDate): EarningsPressRelease? {
        val filing = findEarnings8K(cik, reportDate) ?: run {
            AppLogger.i(TAG, "no Item-2.02 8-K found for CIK $cik within ±$WINDOW_DAYS days of $reportDate")
            return null
        }
        val exhibitUrl = findExhibit991(cik, filing.accession) ?: run {
            AppLogger.i(TAG, "8-K ${filing.accession} has no EX-99 exhibit; skipping press release")
            return null
        }
        val raw = runCatching { edgar.document(exhibitUrl) }
            .onFailure { AppLogger.w(TAG, "exhibit fetch failed ($exhibitUrl): ${it.message}") }
            .getOrNull() ?: return null

        val text = htmlToText(raw).take(MAX_TEXT_CHARS)
        if (text.length < MIN_USABLE_CHARS) {
            AppLogger.w(TAG, "exhibit at $exhibitUrl de-tagged to only ${text.length} chars; treating as unusable")
            return null
        }
        val guidance = extractGuidanceSentences(text)
        AppLogger.i(
            TAG,
            "press release for CIK $cik: ${text.length} chars, ${guidance.size} guidance sentence(s), $exhibitUrl",
        )
        return EarningsPressRelease(
            sourceUrl = exhibitUrl,
            filedDate = filing.filingDate,
            itemCodes = filing.items,
            text = text,
            guidanceSentences = guidance,
        )
    }

    private data class Filing(
        val accession: String,
        val filingDate: LocalDate,
        val items: String?,
    )

    /**
     * Walk `submissions/CIK{cik}.json` for the 8-K nearest [reportDate].
     * Filings tagged with item **2.02** are the earnings releases; we
     * strongly prefer those and only fall back to an untagged 8-K when no
     * 2.02 exists in the window.
     */
    private suspend fun findEarnings8K(cik: String, reportDate: LocalDate): Filing? {
        val root = loadSubmissions(cik) ?: return null
        val recent = root.getAsJsonObject("filings")?.getAsJsonObject("recent") ?: return null
        val accessions = recent.getAsJsonArray("accessionNumber") ?: return null
        val forms = recent.getAsJsonArray("form") ?: return null
        val dates = recent.getAsJsonArray("filingDate") ?: return null
        val items = recent.getAsJsonArray("items")

        val candidates = mutableListOf<Filing>()
        val n = minOf(accessions.size(), forms.size(), dates.size())
        for (i in 0 until n) {
            runCatching {
                val form = forms[i].asStringOrNull() ?: return@runCatching
                if (form != "8-K" && form != "8-K/A") return@runCatching
                val date = LocalDate.parse(dates[i].asStringOrNull() ?: return@runCatching)
                if (kotlin.math.abs(ChronoUnit.DAYS.between(date, reportDate)) > WINDOW_DAYS) return@runCatching
                candidates += Filing(
                    accession = accessions[i].asStringOrNull() ?: return@runCatching,
                    filingDate = date,
                    items = items?.takeIf { i < it.size() }?.get(i)?.asStringOrNull(),
                )
            }
        }
        if (candidates.isEmpty()) return null

        // Item 2.02 = "Results of Operations and Financial Condition".
        // That code is the single most reliable earnings-8-K signal EDGAR
        // gives us, so a 2.02 filing always wins over a plain 8-K even if
        // the plain one is closer to the report date.
        val results = candidates.filter { it.items?.contains(EARNINGS_ITEM_CODE) == true }
        val pool = results.ifEmpty { candidates }
        return pool.minByOrNull { kotlin.math.abs(ChronoUnit.DAYS.between(it.filingDate, reportDate)) }
    }

    /**
     * Read the filing's `index.json` directory listing and pick the
     * Exhibit 99.1 document. Selection order:
     *  1. `type` starting with "EX-99.1" (the canonical earnings release)
     *  2. any `type` starting with "EX-99"
     *  3. a filename matching ex-?99 with an html extension
     * Non-HTML exhibits (graphics, XBRL sidecars) are excluded throughout.
     */
    private suspend fun findExhibit991(cik: String, accession: String): String? {
        val accnNoDash = accession.replace("-", "")
        // Archives paths use the CIK with leading zeros stripped.
        val cikTrimmed = cik.trimStart('0').ifEmpty { cik }
        val dirUrl = "$ARCHIVES_BASE/$cikTrimmed/$accnNoDash"
        val listing = runCatching { edgar.document("$dirUrl/index.json") }
            .onFailure { AppLogger.w(TAG, "index.json fetch failed for $accession: ${it.message}") }
            .getOrNull() ?: return null

        val itemsArr = runCatching {
            JsonParser.parseString(listing).asJsonObject
                .getAsJsonObject("directory")
                ?.getAsJsonArray("item")
        }.getOrNull() ?: return null

        data class Doc(val name: String, val type: String)
        val docs = mutableListOf<Doc>()
        for (el in itemsArr) {
            runCatching {
                val o = el.asJsonObject
                val name = o.get("name")?.asStringOrNull() ?: return@runCatching
                val type = o.get("type")?.asStringOrNull().orEmpty()
                docs += Doc(name, type)
            }
        }
        val htmlDocs = docs.filter { it.name.endsWith(".htm", true) || it.name.endsWith(".html", true) }

        val pick = htmlDocs.firstOrNull { it.type.startsWith("EX-99.1", ignoreCase = true) }
            ?: htmlDocs.firstOrNull { it.type.startsWith("EX-99", ignoreCase = true) }
            ?: htmlDocs.firstOrNull { EX99_NAME_RE.containsMatchIn(it.name) }
            ?: return null
        return "$dirUrl/${pick.name}"
    }

    private suspend fun loadSubmissions(cik: String): JsonObject? {
        val now = System.currentTimeMillis()
        submissionsCache[cik]?.let {
            if (now - it.fetchedAt < CACHE_TTL_MILLIS) return it.root
        }
        val raw = runCatching { edgar.submissions(cik) }
            .onFailure { AppLogger.w(TAG, "submissions fetch failed for $cik: ${it.message}") }
            .getOrNull() ?: return null
        return runCatching { JsonParser.parseString(raw).asJsonObject }
            .onFailure { AppLogger.w(TAG, "submissions parse failed for $cik: ${it.message}") }
            .getOrNull()
            ?.also { submissionsCache[cik] = SubmissionsEntry(it, now) }
    }

    /**
     * De-tag filer HTML into readable text.
     *
     * Table cells are joined with " | " and rows broken with newlines on
     * purpose — the segment-revenue and guidance tables are the most
     * valuable part of a release, and a model reads a pipe-delimited grid
     * far better than the space-mashed run-on that naive tag stripping
     * produces.
     */
    internal fun htmlToText(html: String): String {
        var s = html
        // Drop non-content blocks entirely, including their text.
        s = SCRIPT_STYLE_RE.replace(s, " ")
        s = COMMENT_RE.replace(s, " ")
        // Collapse the SOURCE's own whitespace before introducing any line
        // breaks of our own. Filer HTML hard-wraps prose inside a single
        // <p>, and if those raw newlines survive, every wrapped sentence
        // arrives split in half — which silently destroyed guidance
        // extraction: "Revenue is expected to be" (verb, no figure) and
        // "$65.0 billion, plus or minus 2%." (figure, no verb) each fail
        // the filter, so the single most important sentence in the release
        // was dropped. After this, only block-level tags create newlines.
        s = WHITESPACE_RUN_RE.replace(s, " ")
        // Cell separators before the generic row/block breaks, so a
        // </td></tr> pair yields "… |\n" rather than swallowing one.
        s = CELL_END_RE.replace(s, " | ")
        s = BLOCK_BREAK_RE.replace(s, "\n")
        // Everything else that's still a tag goes away.
        s = TAG_RE.replace(s, "")
        s = unescapeEntities(s)
        // Normalize per line: collapse runs of whitespace, tidy the
        // pipe separators left by empty cells, drop lines with no
        // content. Empty-cell runs are extremely common in filer tables.
        return s.lineSequence()
            .map { line ->
                // U+00A0 non-breaking space appears literally (not only as
                // &nbsp;) throughout filer HTML.
                line.replace('\u00A0', ' ')
                    .replace(WHITESPACE_RUN_RE, " ")
                    .replace(EMPTY_CELLS_RE, " | ")
                    .trim()
                    .trim('|', ' ')
                    .trim()
            }
            .filter { it.isNotBlank() && it.any { ch -> ch.isLetterOrDigit() } }
            .joinToString("\n")
            .replace(BLANK_LINES_RE, "\n")
    }

    /**
     * Sentences that look like forward guidance. Requires BOTH a
     * forward-looking verb and a figure — "we expect to continue
     * investing in our people" is not guidance, "we expect revenue of
     * $4.2 billion" is. Capped so a boilerplate-heavy safe-harbour
     * section can't crowd out the real content.
     */
    internal fun extractGuidanceSentences(text: String): List<String> {
        val sentences = text.split(SENTENCE_SPLIT_RE)
        return sentences.asSequence()
            .map { it.trim() }
            .filter { it.length in 30..600 }
            .filter { GUIDANCE_VERB_RE.containsMatchIn(it) }
            .filter { FIGURE_RE.containsMatchIn(it) }
            // Safe-harbour boilerplate uses the same verbs but carries no
            // real numbers about the business; drop the obvious cases.
            .filterNot { SAFE_HARBOUR_RE.containsMatchIn(it) }
            .distinct()
            .take(MAX_GUIDANCE_SENTENCES)
            .toList()
    }

    private fun unescapeEntities(s: String): String {
        var out = s
        for ((entity, replacement) in ENTITIES) out = out.replace(entity, replacement)
        // Numeric entities — decimal and hex.
        out = NUMERIC_ENTITY_RE.replace(out) { m ->
            val body = m.groupValues[1]
            val code = if (body.startsWith("x", true)) {
                body.drop(1).toIntOrNull(16)
            } else {
                body.toIntOrNull()
            }
            if (code != null && code in 1..0x10FFFF) String(Character.toChars(code)) else " "
        }
        return out
    }

    /** JsonNull-safe string read: EDGAR emits explicit nulls for optional
     *  fields, and `asString` throws on JsonNull. */
    private fun com.google.gson.JsonElement.asStringOrNull(): String? =
        if (isJsonNull) null else runCatching { asString }.getOrNull()

    internal companion object {
        const val TAG = "EdgarPressRelease"
        const val ARCHIVES_BASE = "https://www.sec.gov/Archives/edgar/data"
        /** 8-K Item 2.02 — Results of Operations and Financial Condition. */
        const val EARNINGS_ITEM_CODE = "2.02"
        /** How far from the report date to look for the 8-K. Releases
         *  normally land same-day; a Friday-evening print can be dated
         *  the following Monday. */
        const val WINDOW_DAYS = 10L
        /** Cap on de-tagged release text handed to the model. Releases run
         *  10-60KB of text; this keeps a full release for nearly every
         *  filer while bounding the prompt for the pathological ones. */
        const val MAX_TEXT_CHARS = 60_000
        /** Below this, de-tagging clearly failed (or the exhibit was a
         *  cover page) and the "press release" would be noise. */
        const val MIN_USABLE_CHARS = 400
        const val MAX_GUIDANCE_SENTENCES = 25
        const val CACHE_TTL_MILLIS = 6L * 60L * 60L * 1000L // 6h

        private val SCRIPT_STYLE_RE =
            Regex("<(script|style)\\b[^>]*>.*?</\\1>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        private val COMMENT_RE = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
        private val CELL_END_RE = Regex("</(td|th)\\s*>", RegexOption.IGNORE_CASE)
        private val BLOCK_BREAK_RE = Regex(
            "<\\s*/?\\s*(br|p|div|tr|table|h[1-6]|li|ul|ol|thead|tbody|tfoot)\\b[^>]*>",
            RegexOption.IGNORE_CASE,
        )
        private val TAG_RE = Regex("<[^>]*>", RegexOption.DOT_MATCHES_ALL)
        private val WHITESPACE_RUN_RE = Regex("\\s+")
        /** " | | | " left behind by empty table cells. */
        private val EMPTY_CELLS_RE = Regex("(\\s*\\|\\s*){2,}")
        private val BLANK_LINES_RE = Regex("\n{2,}")
        private val NUMERIC_ENTITY_RE = Regex("&#(x?[0-9A-Fa-f]+);")
        private val ENTITIES = listOf(
            "&nbsp;" to " ", "&amp;" to "&", "&lt;" to "<", "&gt;" to ">",
            "&quot;" to "\"", "&apos;" to "'", "&mdash;" to "—", "&ndash;" to "–",
            "&rsquo;" to "'", "&lsquo;" to "'", "&ldquo;" to "\"", "&rdquo;" to "\"",
            "&bull;" to "·", "&middot;" to "·", "&hellip;" to "…", "&trade;" to "™",
            "&reg;" to "®", "&copy;" to "©", "&percnt;" to "%", "&dollar;" to "$",
        )
        private val SENTENCE_SPLIT_RE = Regex("(?<=[.!?])\\s+(?=[A-Z(\"$])|\n")
        /**
         * A genuinely forward-looking verb is REQUIRED. Period phrases
         * ("for the third quarter", "fiscal 2026") were in this set
         * originally and matched the results dateline — "NVIDIA today
         * reported revenue for the third quarter … of $57.0 billion" was
         * being filed under "Forward guidance", which is precisely the
         * estimate-vs-actual confusion the facts contract exists to
         * prevent.
         */
        private val GUIDANCE_VERB_RE = Regex(
            "\\b(expect|expects|expected|guidance|outlook|forecast|forecasts|forecasting|" +
                "anticipate|anticipates|anticipated|project|projects|projected|projecting|" +
                "targeting|we see|intend to|plan to|will be approximately)\\b",
            RegexOption.IGNORE_CASE,
        )
        /** A dollar figure, a percentage, or a spelled-out magnitude. */
        private val FIGURE_RE = Regex(
            "(\\$\\s?[\\d,.]+)|(\\d[\\d,.]*\\s?%)|(\\b[\\d,.]+\\s?(billion|million|bn|mm)\\b)",
            RegexOption.IGNORE_CASE,
        )
        private val SAFE_HARBOUR_RE = Regex(
            "\\b(forward[- ]looking statements|Private Securities Litigation Reform Act|" +
                "Risk Factors|undue reliance|Section 27A|Section 21E)\\b",
            RegexOption.IGNORE_CASE,
        )
        private val EX99_NAME_RE = Regex("ex[-_]?99", RegexOption.IGNORE_CASE)
    }
}
