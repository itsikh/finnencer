package io.itsikh.finnencer.data.ai

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.itsikh.finnencer.data.dao.EarningsDao
import io.itsikh.finnencer.data.dao.NewsDao
import io.itsikh.finnencer.data.dao.PodcastDao
import io.itsikh.finnencer.data.entity.Podcast
import io.itsikh.finnencer.data.entity.PodcastGenerationStatus
import io.itsikh.finnencer.data.entity.PodcastSourceType
import io.itsikh.finnencer.logging.AppLogger
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Produces ONE summary across a user-selected bundle of articles, then
 * (optionally) renders it as a target-duration multi-voice podcast.
 *
 * Page presets reuse the existing earnings tiers: 2 / 5 / 10. Podcast
 * duration is expressed in minutes; the dialogue prompt is steered to
 * land within ±15% of the target by converting minutes → character budget
 * at ~600 chars/min (spoken-English heuristic).
 */
@Singleton
class BundleSummarizer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val router: AiRouter,
    private val tts: GeminiTts,
    private val newsDao: NewsDao,
    private val podcastDao: PodcastDao,
    private val earningsDao: EarningsDao,
    private val promptPrefs: PromptPreferences,
) {

    enum class Pages(val target: Int, val maxTokens: Int) {
        TWO(2, 2_000), FIVE(5, 4_500), TEN(10, 9_000)
    }

    enum class PodcastMinutes(val minutes: Int) {
        FIVE(5), TEN(10), FIFTEEN(15), TWENTY(20), THIRTY(30);
        val charBudget: Int get() = minutes * 600 // ~150 wpm spoken English
    }

    /** Output of [summarizeText]: the prose plus the id of whichever model
     *  actually answered (which may be a fallback if the primary failed). */
    data class SummaryResult(val text: String, val modelId: String)

    /**
     * Text summary of [articleIds] — returns the prose blob plus the
     * model that produced it (caller can persist for attribution).
     * Cached side-effect: the caller persists into [SummaryVersion]s
     * table (see C·1).
     */
    suspend fun summarizeText(articleIds: List<String>, pages: Pages, customPrompt: String?): SummaryResult {
        require(articleIds.isNotEmpty()) { "selection must be non-empty" }
        val articles = articleIds.mapNotNull { newsDao.getArticle(it) }
        val bundle = buildString {
            articles.forEachIndexed { i, a ->
                append("Article ").append(i + 1).append(":\n")
                append("  Title: ").append(a.title).append('\n')
                a.snippet?.takeIf { it.isNotBlank() }?.let {
                    append("  Snippet: ").append(it.replace("\n", " ")).append('\n')
                }
                append("  Source: ").append(a.sourceName).append('\n')
                append("  Ticker: ").append(a.primaryTickerSymbol ?: "?").append('\n')
                append("  URL: ").append(a.url).append('\n')
                append('\n')
            }
        }
        val baseSystem = buildString {
            append(BASE_SUMMARY_SYSTEM)
            append("\n\nTarget length: about ").append(pages.target).append(" pages of dense prose ")
            append("(~").append(pages.target * 350).append(" words). ")
            append("Synthesize across all ").append(articles.size).append(" articles — do NOT list per-article summaries.")
        }
        val system = promptPrefs.applyExtras(
            base = baseSystem,
            extra = promptPrefs.get(AiUsage.SUMMARY),
            perCallCustom = customPrompt,
        )
        val completion = router.complete(
            usage = AiUsage.SUMMARY,
            system = system,
            userMessage = bundle,
            maxTokens = pages.maxTokens,
            temperature = 0.4,
        )
        return SummaryResult(text = completion.text.trim(), modelId = completion.modelUsed.id)
    }

    /**
     * Build a Host/Analyst dialogue script targeting [minutes] of spoken
     * runtime, then render it to a WAV file. Returns the podcast row id.
     */
    suspend fun summarizeToPodcast(
        articleIds: List<String>,
        minutes: PodcastMinutes,
        customPrompt: String?,
        existingPodcastId: Long? = null,
        onPodcastIdAssigned: suspend (Long) -> Unit = {},
    ): Long {
        require(articleIds.isNotEmpty()) { "selection must be non-empty" }
        val articles = articleIds.mapNotNull { newsDao.getArticle(it) }
        val titles = articles.take(3).joinToString(", ") { it.title.take(60) }
        val title = "${articles.firstOrNull()?.primaryTickerSymbol ?: "Custom"}  ·  ${minutes.minutes} min  ·  $titles"
        val source = buildString {
            articles.forEachIndexed { i, a ->
                append("--- Article ").append(i + 1).append(" ---\n")
                append("Title: ").append(a.title).append('\n')
                a.snippet?.takeIf { it.isNotBlank() }?.let {
                    append("Snippet: ").append(it).append('\n')
                }
                append("Source: ").append(a.sourceName)
                append(" · Ticker: ").append(a.primaryTickerSymbol ?: "?").append('\n')
                append('\n')
            }
        }
        return renderPodcast(
            title = title.take(120),
            sourceId = articleIds.joinToString(","),
            sourceMaterial = source,
            minutes = minutes,
            customPrompt = customPrompt,
            existingPodcastId = existingPodcastId,
            onPodcastIdAssigned = onPodcastIdAssigned,
        )
    }

    /**
     * Render a podcast whose script-writer source is the markdown body of
     * an [EarningsReport] (BRIEF / STANDARD / DEEP). Used by the per-stock
     * "Make podcast" affordance on the past-earnings card.
     */
    suspend fun podcastFromEarningsReport(
        reportId: Long,
        minutes: PodcastMinutes,
        customPrompt: String?,
        existingPodcastId: Long? = null,
        onPodcastIdAssigned: suspend (Long) -> Unit = {},
    ): Long {
        val report = earningsDao.getReport(reportId)
            ?: error("EarningsReport $reportId not found")
        return renderPodcast(
            title = "${report.tickerSymbol}  ·  ${minutes.minutes} min  ·  ${report.title.take(80)}",
            sourceId = "earnings_report:$reportId",
            sourceMaterial = report.contentMarkdown,
            minutes = minutes,
            customPrompt = customPrompt,
            existingPodcastId = existingPodcastId,
            onPodcastIdAssigned = onPodcastIdAssigned,
        )
    }

    /**
     * Variant of [summarizeToPodcast] that uses an already-produced summary
     * blob as the script writer's source material instead of the raw article
     * bundle. Used by the combo "summary + podcast" flow so the podcast
     * narrative aligns with the just-generated summary the user sees in the
     * Tasks card.
     */
    suspend fun podcastFromSummary(
        articleIds: List<String>,
        summaryText: String,
        minutes: PodcastMinutes,
        customPrompt: String?,
        existingPodcastId: Long? = null,
        onPodcastIdAssigned: suspend (Long) -> Unit = {},
    ): Long {
        val articles = articleIds.mapNotNull { newsDao.getArticle(it) }
        val ticker = articles.firstOrNull()?.primaryTickerSymbol ?: "Custom"
        val titles = articles.take(3).joinToString(", ") { it.title.take(60) }
        val title = "$ticker  ·  ${minutes.minutes} min  ·  $titles"
        return renderPodcast(
            title = title.take(120),
            sourceId = articleIds.joinToString(","),
            sourceMaterial = summaryText,
            minutes = minutes,
            customPrompt = customPrompt,
            existingPodcastId = existingPodcastId,
            onPodcastIdAssigned = onPodcastIdAssigned,
        )
    }

    private suspend fun renderPodcast(
        title: String,
        sourceId: String,
        sourceMaterial: String,
        minutes: PodcastMinutes,
        customPrompt: String?,
        existingPodcastId: Long? = null,
        onPodcastIdAssigned: suspend (Long) -> Unit = {},
    ): Long {
        // Retry path: if the caller passed a previously-created podcast
        // row id (because a prior attempt failed), reset that row to
        // PENDING and overwrite in place rather than insert a duplicate
        // (#39 — "spam bug"). Falls back to a fresh insert if the row no
        // longer exists.
        val existing = existingPodcastId?.let { podcastDao.get(it) }
        val id: Long = if (existing != null) {
            podcastDao.update(
                existing.copy(
                    title = title,
                    sourceType = PodcastSourceType.CUSTOM_TEXT.name,
                    sourceId = sourceId,
                    characterCount = minutes.charBudget,
                    filePath = null,
                    durationMs = null,
                    status = PodcastGenerationStatus.PENDING.name,
                    generationError = null,
                )
            )
            AppLogger.i(TAG, "reusing podcast row ${existing.id} for retry")
            existing.id
        } else {
            val pending = Podcast(
                sourceType = PodcastSourceType.CUSTOM_TEXT.name,
                sourceId = sourceId,
                title = title,
                voiceHost = GeminiTts.VoicePair.Default.host,
                voiceAnalyst = GeminiTts.VoicePair.Default.analyst,
                filePath = null,
                durationMs = null,
                characterCount = minutes.charBudget,
                status = PodcastGenerationStatus.PENDING.name,
                generationError = null,
                createdAtMillis = System.currentTimeMillis(),
            )
            podcastDao.insert(pending)
        }
        // Notify caller (worker) of the row id IMMEDIATELY so it can
        // persist on AiJob.resultRefId. A process kill anywhere below
        // would otherwise orphan this row and the next retry would
        // create a fresh duplicate (#39).
        runCatching { onPodcastIdAssigned(id) }
            .onFailure { AppLogger.w(TAG, "onPodcastIdAssigned callback failed: ${it.message}") }

        runCatching {
            podcastDao.update(podcastDao.get(id)!!.copy(status = PodcastGenerationStatus.GENERATING.name))

            val baseScriptSystem = buildString {
                append(DIALOGUE_SYSTEM)
                append("\n\nTarget duration: about ").append(minutes.minutes).append(" minutes when spoken aloud ")
                append("(~").append(minutes.charBudget).append(" characters of dialogue). ")
                append("A ").append(minutes.minutes).append("-minute podcast typically contains ")
                append(minutes.minutes * 2).append("–").append(minutes.minutes * 3)
                append(" distinct Host/Analyst exchanges. Pace the conversation so the ")
                append("entire script lands within ±10% of that target.\n\n")
                append("LENGTH IS A HARD REQUIREMENT. You MUST produce at least ")
                append((minutes.charBudget * TARGET_THRESHOLD).toInt()).append(" characters of dialogue. ")
                append("Failing the minimum is unacceptable — count your characters as you go. ")
                append("Do NOT wrap up early. If you feel the conversation is nearing an end ")
                append("before reaching the target, instead introduce a new angle: ")
                append("a competing analyst view, a historical analog, second-order effects, ")
                append("competitive positioning, or what to watch in the next quarter. ")
                append("End ONLY when you've covered the full duration.")
            }
            val scriptSystem = promptPrefs.applyExtras(
                base = baseScriptSystem,
                extra = promptPrefs.get(AiUsage.PODCAST_SCRIPT),
                perCallCustom = customPrompt,
            )
            // ~3.5 chars/token average for English dialogue. /2.5 leaves
            // headroom so the model isn't truncated by maxTokens at the
            // target length (#34/#35). Capped well under Anthropic's
            // 16k-token output limit on Sonnet 4.6 / Opus 4.7.
            val maxTokens = (minutes.charBudget / 2.5).toInt().coerceIn(2000, 12000)
            val script = generateScript(
                scriptSystem = scriptSystem,
                source = sourceMaterial,
                maxTokens = maxTokens,
                targetChars = minutes.charBudget,
            )

            val outputDir = File(context.filesDir, "podcasts").apply { mkdirs() }
            val outputFile = File(outputDir, "${UUID.randomUUID()}.wav")
            val result = tts.synthesizeDialogue(
                script = script,
                voices = GeminiTts.VoicePair.Default,
                outputFile = outputFile,
            )

            podcastDao.update(
                podcastDao.get(id)!!.copy(
                    filePath = result.file.absolutePath,
                    durationMs = result.durationMs,
                    status = PodcastGenerationStatus.READY.name,
                    generationError = null,
                )
            )
            AppLogger.i(TAG, "bundle podcast $id ready (${result.bytes / 1024}KB, ${result.durationMs / 1000}s)")
        }.onFailure { t ->
            val friendly = FriendlyError.describe(t, stage = "podcast")
            podcastDao.update(
                podcastDao.get(id)!!.copy(
                    status = PodcastGenerationStatus.FAILED.name,
                    generationError = friendly,
                )
            )
            AppLogger.e(TAG, "bundle podcast $id failed: $friendly", t)
        }
        return id
    }

    /**
     * Drive the dialogue model toward [targetChars] of script. Both the
     * initial pass AND each continuation pass run through the same
     * 10-attempt linear-backoff retry helper, so a single transient
     * network drop on the *very first* request no longer fails the
     * whole podcast (#41 follow-up). Up to [MAX_CONTINUATIONS]
     * continuation passes are run whenever the accumulated length is
     * under [TARGET_THRESHOLD] × target.
     *
     * Total worst-case wait per pass: 5+10+…+50 = 275s. An initial-pass
     * failure that exhausts all retries throws and the surrounding
     * runCatching in renderPodcast marks the row FAILED with the
     * friendly error message.
     */
    private suspend fun generateScript(
        scriptSystem: String,
        source: String,
        maxTokens: Int,
        targetChars: Int,
    ): String {
        val initial = scriptCallWithRetry(scriptSystem, source, maxTokens, passLabel = "initial")
            ?: throw java.io.IOException("Podcast script call failed after $CONTINUATION_RETRIES retries")
        val combined = StringBuilder(initial.text.trim())
        var stop = initial.stopReason
        var rounds = 0
        val minAcceptable = (targetChars * TARGET_THRESHOLD).toInt()
        AppLogger.i(TAG, "podcast script initial pass: len=${combined.length}/$targetChars stop=$stop")

        while (rounds < MAX_CONTINUATIONS && combined.length < minAcceptable) {
            val reason = when (stop) {
                "max_tokens" -> "Your previous response was cut off at the token limit. Continue seamlessly from the last partial line."
                else -> "The script is still ${minAcceptable - combined.length} characters short of the required minimum. Continue the dialogue with new angles until the full target length is reached."
            }
            val continuationSystem = buildString {
                append(scriptSystem)
                append("\n\nCONTINUATION INSTRUCTIONS: ").append(reason)
                append(" Pick up mid-conversation without re-introducing the company or quarter — assume the listener heard everything so far.")
            }
            val continuationUser = buildString {
                append("Source material:\n").append(source)
                append("\n\nScript so far (do not repeat any line):\n").append(combined)
            }
            val next = scriptCallWithRetry(continuationSystem, continuationUser, maxTokens, passLabel = "cont ${rounds + 1}")
            if (next == null) {
                AppLogger.w(TAG, "podcast script continuation ${rounds + 1} gave up after $CONTINUATION_RETRIES retries; using partial script (len=${combined.length}/$targetChars)")
                break
            }
            val cont = next.text.trim()
            if (cont.isBlank()) {
                AppLogger.w(TAG, "podcast script continuation ${rounds + 1} returned blank text; stopping")
                break
            }
            // Glue with a single newline so the speaker-label regex in
            // GeminiTts.chunkAtSpeakerBoundaries still segments correctly.
            combined.append('\n').append(cont)
            stop = next.stopReason
            rounds++
            AppLogger.i(TAG, "podcast script continuation $rounds ok (len=${combined.length}/$targetChars stop=$stop)")
        }

        if (combined.length < minAcceptable) {
            AppLogger.w(TAG, "podcast script under target: len=${combined.length}/$targetChars after $rounds continuation(s)")
        }
        return trimTrailingPartialLine(combined.toString())
    }

    /**
     * Retry a podcast-script call up to [CONTINUATION_RETRIES] times with
     * linear 5n-second backoff (5s, 10s, 15s, … 50s). Used for BOTH the
     * initial pass and the continuation passes so a single dropped
     * request on flaky network doesn't fail the whole podcast (#41).
     * [passLabel] tags log lines ("initial" / "cont 1" / "cont 2").
     * Returns the result or null if every attempt failed.
     */
    private suspend fun scriptCallWithRetry(
        system: String,
        user: String,
        maxTokens: Int,
        passLabel: String,
    ): AiCompletion? {
        var lastErr: Throwable? = null
        for (attempt in 1..CONTINUATION_RETRIES) {
            try {
                return router.complete(
                    usage = AiUsage.PODCAST_SCRIPT,
                    system = system,
                    userMessage = user,
                    maxTokens = maxTokens,
                    temperature = 0.6,
                )
            } catch (ce: kotlinx.coroutines.CancellationException) {
                // Cooperative cancellation — let the outer job handle it
                // rather than silently swallowing.
                throw ce
            } catch (t: Throwable) {
                lastErr = t
                if (attempt >= CONTINUATION_RETRIES) break
                val backoffMs = attempt * CONTINUATION_BACKOFF_STEP_MS // 5s, 10s, 15s, ...
                AppLogger.w(TAG, "podcast script $passLabel attempt $attempt/$CONTINUATION_RETRIES failed (${t.javaClass.simpleName}: ${t.message}); waiting ${backoffMs / 1000}s")
                kotlinx.coroutines.delay(backoffMs)
            }
        }
        AppLogger.w(TAG, "podcast script $passLabel exhausted all $CONTINUATION_RETRIES retries: ${lastErr?.message}")
        return null
    }

    /**
     * If the script's final non-empty line doesn't end with a sentence
     * terminator, drop it. Prevents the user from hearing the host or
     * analyst cut off mid-sentence at the end of a podcast (#35).
     */
    private fun trimTrailingPartialLine(script: String): String {
        val trimmed = script.trimEnd()
        val lastNl = trimmed.lastIndexOf('\n')
        if (lastNl < 0) return trimmed
        val lastLine = trimmed.substring(lastNl + 1).trimEnd()
        val endsCleanly = lastLine.isNotEmpty() && lastLine.last() in SENTENCE_TERMINATORS
        return if (endsCleanly) trimmed else trimmed.substring(0, lastNl).trimEnd()
    }

    private companion object {
        const val TAG = "BundleSummarizer"
        const val MAX_CONTINUATIONS = 3
        const val TARGET_THRESHOLD = 0.90
        const val CONTINUATION_RETRIES = 10
        const val CONTINUATION_BACKOFF_STEP_MS = 5_000L
        private val SENTENCE_TERMINATORS = setOf('.', '!', '?', '"', '\'', ')', ']')

        const val BASE_SUMMARY_SYSTEM = """
You are summarizing a bundle of financial news articles for an active investor.

Synthesize across all articles into ONE coherent narrative. Do not list per-article
summaries. The reader has chosen these specific articles — work out why they belong
together (a single event, a converging trend, a sector story, etc.) and build the
summary around that thread.

For every claim, name the source (e.g. "Bloomberg reports", "the 8-K says"). Lead
with what's actionable. Sectionalize only if the page target is 5+ pages."""

        const val DIALOGUE_SYSTEM = """
You are a financial-news podcast script writer.

Convert the supplied bundle of articles into a two-person podcast dialogue between:
 - Host: a sharp finance interviewer who asks framing questions, summarizes, and
         pulls the analyst forward
 - Analyst: a senior equity analyst who gives data-rich answers with context

Format STRICTLY as alternating lines, each starting with "Host:" or "Analyst:"
at the beginning of the line. Plain text only — no markdown headings, no SSML,
no stage directions.

Synthesize across articles — don't read them one by one. Start with what the
listener should walk away knowing, then drill into evidence. End on next-watch
catalysts. Numbers should be spoken naturally ("about forty-four billion")
alongside their digit form."""
    }
}
