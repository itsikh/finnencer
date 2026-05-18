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
    ): Long {
        val report = earningsDao.getReport(reportId)
            ?: error("EarningsReport $reportId not found")
        return renderPodcast(
            title = "${report.tickerSymbol}  ·  ${minutes.minutes} min  ·  ${report.title.take(80)}",
            sourceId = "earnings_report:$reportId",
            sourceMaterial = report.contentMarkdown,
            minutes = minutes,
            customPrompt = customPrompt,
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
        )
    }

    private suspend fun renderPodcast(
        title: String,
        sourceId: String,
        sourceMaterial: String,
        minutes: PodcastMinutes,
        customPrompt: String?,
    ): Long {
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
        val id = podcastDao.insert(pending)

        runCatching {
            podcastDao.update(podcastDao.get(id)!!.copy(status = PodcastGenerationStatus.GENERATING.name))

            val baseScriptSystem = buildString {
                append(DIALOGUE_SYSTEM)
                append("\n\nTarget duration: about ").append(minutes.minutes).append(" minutes when spoken aloud ")
                append("(~").append(minutes.charBudget).append(" characters of dialogue). ")
                append("Pace the conversation so the entire script lands within ±15% of that target. ")
                append("Number of turns should scale with duration (rule of thumb: ")
                append(minutes.minutes * 2).append(" total turns).\n\n")
                append("LENGTH IS A HARD REQUIREMENT. You MUST produce at least ")
                append((minutes.charBudget * 0.85).toInt()).append(" characters of dialogue. ")
                append("Do NOT wrap up early. If you feel the conversation is nearing an end ")
                append("before reaching the target, instead introduce a new angle: ")
                append("a competing analyst view, a historical analog, second-order effects, ")
                append("or what to watch in the next quarter. End ONLY when you've covered the full duration.")
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
            podcastDao.update(
                podcastDao.get(id)!!.copy(
                    status = PodcastGenerationStatus.FAILED.name,
                    generationError = t.message ?: "unknown",
                )
            )
            AppLogger.e(TAG, "bundle podcast $id failed", t)
        }
        return id
    }

    /**
     * Drive the dialogue model toward [targetChars] of script. If the
     * model stops with `max_tokens`, request a seamless continuation
     * (up to [MAX_CONTINUATIONS] follow-ups). If the model finishes
     * naturally but well short of target, do one extension pass to
     * push it closer. Finally trim a trailing mid-sentence line so we
     * never hand TTS a cut-off thought (#34/#35).
     */
    private suspend fun generateScript(
        scriptSystem: String,
        source: String,
        maxTokens: Int,
        targetChars: Int,
    ): String {
        val initial = router.complete(
            usage = AiUsage.PODCAST_SCRIPT,
            system = scriptSystem,
            userMessage = source,
            maxTokens = maxTokens,
            temperature = 0.6,
        )
        val combined = StringBuilder(initial.text.trim())
        var stop = initial.stopReason
        var rounds = 0
        val minAcceptable = (targetChars * 0.85).toInt()

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
            val next = runCatching {
                router.complete(
                    usage = AiUsage.PODCAST_SCRIPT,
                    system = continuationSystem,
                    userMessage = continuationUser,
                    maxTokens = maxTokens,
                    temperature = 0.6,
                )
            }.getOrNull() ?: break
            val cont = next.text.trim()
            if (cont.isBlank()) break
            // Glue with a single newline so the speaker-label regex in
            // GeminiTts.chunkAtSpeakerBoundaries still segments correctly.
            combined.append('\n').append(cont)
            stop = next.stopReason
            rounds++
            AppLogger.i(TAG, "podcast script continuation $rounds (len=${combined.length}/$targetChars)")
        }

        return trimTrailingPartialLine(combined.toString())
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
        const val MAX_CONTINUATIONS = 2
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
