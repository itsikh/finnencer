package io.itsikh.finnencer.data.ai

import android.content.Context
import io.itsikh.finnencer.logging.AppLogger as Log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.itsikh.finnencer.data.dao.EarningsDao
import io.itsikh.finnencer.data.dao.PodcastDao
import io.itsikh.finnencer.data.entity.Podcast
import io.itsikh.finnencer.data.entity.PodcastGenerationStatus
import io.itsikh.finnencer.data.entity.PodcastSourceType
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Coordinates the three-step podcast pipeline:
 *  1. Claude (per-usage routed model) writes a Host/Analyst dialogue
 *     script from the source
 *  2. Gemini multi-speaker TTS renders the script to WAV
 *  3. Persist [Podcast] row pointing at the on-disk file
 *
 * The Podcast row is created in PENDING state up front so the UI has a row
 * to observe immediately. Status flips to GENERATING -> READY or FAILED.
 */
@Singleton
class PodcastGenerator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val router: AiRouter,
    private val tts: GeminiTts,
    private val podcastDao: PodcastDao,
    private val earningsDao: EarningsDao,
) {

    /**
     * Kick off generation for an earnings report. Returns the Podcast row id
     * immediately; observe the row in the UI to track progress.
     */
    suspend fun generateFromReport(reportId: Long): Long {
        val report = earningsDao.getReport(reportId)
            ?: error("EarningsReport $reportId not found")

        val pending = Podcast(
            sourceType = PodcastSourceType.REPORT.name,
            sourceId = reportId.toString(),
            title = report.title,
            voiceHost = GeminiTts.VoicePair.Default.host,
            voiceAnalyst = GeminiTts.VoicePair.Default.analyst,
            filePath = null,
            durationMs = null,
            characterCount = report.contentMarkdown.length,
            status = PodcastGenerationStatus.PENDING.name,
            generationError = null,
            createdAtMillis = System.currentTimeMillis(),
        )
        val id = podcastDao.insert(pending)

        runCatching {
            podcastDao.update(requirePodcastRow(id).copy(status = PodcastGenerationStatus.GENERATING.name))

            // 1. Dialogue script
            val completion = router.complete(
                usage = AiUsage.PODCAST_SCRIPT,
                system = DIALOGUE_SYSTEM,
                userMessage = report.contentMarkdown,
                // Sized for Sonnet 5's tokenizer (~30% more tokens than
                // 4.6): ~3500 words needs ~6000 output tokens now.
                maxTokens = 6000,
                temperature = 0.6,
            )
            val script = if (completion.stopReason == "max_tokens") {
                // The model hit the output cap mid-sentence. Sending a
                // half-finished line to TTS produces an audibly cut-off
                // ending, so trim back to the last complete dialogue line
                // and render what we have.
                Log.w(TAG, "podcast $id script hit max_tokens; trimming to last complete dialogue line")
                trimTruncatedScript(completion.text)
            } else completion.text

            // 2. TTS
            val outputDir = File(context.filesDir, "podcasts").apply { mkdirs() }
            val outputFile = File(outputDir, "${UUID.randomUUID()}.wav")
            val result = tts.synthesizeDialogue(
                script = script,
                voices = GeminiTts.VoicePair.Default,
                outputFile = outputFile,
            )

            // 3. Persist. NonCancellable so a caller-scope teardown right
            // after TTS finished can't strand a fully-rendered file in a
            // GENERATING row.
            withContext(NonCancellable) {
                podcastDao.update(
                    requirePodcastRow(id).copy(
                        filePath = result.file.absolutePath,
                        durationMs = result.durationMs,
                        status = PodcastGenerationStatus.READY.name,
                        generationError = null,
                    )
                )
            }
            Log.i(TAG, "podcast $id ready (${result.bytes / 1024}KB, ${result.durationMs / 1000}s)")
        }.onFailure { t ->
            val friendly = FriendlyError.describe(t, stage = "podcast")
            // NonCancellable: this runs in the caller's viewModelScope, and
            // when the failure IS a cancellation the suspend DAO calls below
            // would throw immediately, leaving the row stuck GENERATING.
            withContext(NonCancellable) {
                // Null-safe: the row may have been deleted mid-job, and an
                // NPE from inside onFailure would mask the real diagnostic.
                podcastDao.get(id)?.let {
                    podcastDao.update(
                        it.copy(
                            status = PodcastGenerationStatus.FAILED.name,
                            generationError = friendly,
                        )
                    )
                }
            }
            Log.e(TAG, "podcast $id failed: $friendly", t)
            // Rethrow so callers see the failure instead of a normally
            // returned id for a FAILED podcast row.
            throw t
        }

        return id
    }

    /**
     * Cut a max_tokens-truncated script back to the last COMPLETE dialogue
     * line: the last line that starts with "Host:" or "Analyst:" AND ends
     * with terminal punctuation (allowing a trailing closing quote). If no
     * line qualifies, return the script unchanged — a slightly clipped
     * ending beats failing the whole podcast.
     */
    private fun trimTruncatedScript(script: String): String {
        val lines = script.trimEnd().lines()
        val lastComplete = lines.indexOfLast { line ->
            val t = line.trim()
            val body = t.trimEnd('"', '”', ')', ']')
            (t.startsWith("Host:") || t.startsWith("Analyst:")) &&
                (body.endsWith(".") || body.endsWith("!") || body.endsWith("?"))
        }
        if (lastComplete == -1) return script
        return lines.subList(0, lastComplete + 1).joinToString("\n")
    }

    /** The user can delete the podcast row mid-job (library delete /
     *  "Clear failed"); fail with a clear message instead of an NPE. */
    private suspend fun requirePodcastRow(id: Long): Podcast =
        podcastDao.get(id)
            ?: throw IllegalStateException("Podcast $id was deleted while it was being generated")

    private companion object {
        const val TAG = "PodcastGenerator"

        const val DIALOGUE_SYSTEM = """
You are a financial-news podcast script writer.

Convert the supplied written report into a two-person podcast dialogue between:
 - Host: a sharp finance interviewer who asks framing questions, summarizes,
         and pulls the analyst forward
 - Analyst: a senior equity analyst who gives data-rich answers with context

Format STRICTLY as alternating lines, each starting with "Host:" or "Analyst:"
at the beginning of the line. Plain text only — no markdown headings, no SSML,
no stage directions other than the bracket audio tags described below.

Rules:
 - Open with the Host briefly introducing the company and the quarter
 - Get to the numbers quickly: revenue, EPS, guidance, segments
 - Surface the bull and bear cases via direct questions
 - Use natural conversational rhythm (5-15 turns total, max ~3500 words)
 - When the analyst quotes a number, do so verbally ("about forty-four billion")
   alongside the digits — TTS reads digits fine but spoken numbers feel better
 - End with the Host naming the next catalyst to watch
 - No filler ("That's a great question"). Get to substance immediately.
${DefaultPrompts.DIALOGUE_STYLE}"""
    }
}
