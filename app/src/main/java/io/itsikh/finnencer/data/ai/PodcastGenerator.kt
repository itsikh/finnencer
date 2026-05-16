package io.itsikh.finnencer.data.ai

import android.content.Context
import io.itsikh.finnencer.logging.AppLogger as Log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.itsikh.finnencer.data.dao.EarningsDao
import io.itsikh.finnencer.data.dao.PodcastDao
import io.itsikh.finnencer.data.entity.Podcast
import io.itsikh.finnencer.data.entity.PodcastGenerationStatus
import io.itsikh.finnencer.data.entity.PodcastSourceType
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Coordinates the three-step podcast pipeline:
 *  1. Claude Opus 4.7 writes a Host/Analyst dialogue script from the source
 *  2. Gemini 2.5 Flash multi-speaker TTS renders the script to WAV
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
            podcastDao.update(podcastDao.get(id)!!.copy(status = PodcastGenerationStatus.GENERATING.name))

            // 1. Dialogue script
            val script = router.complete(
                usage = AiUsage.PODCAST_SCRIPT,
                system = DIALOGUE_SYSTEM,
                userMessage = report.contentMarkdown,
                maxTokens = 4500,
                temperature = 0.6,
            ).text

            // 2. TTS
            val outputDir = File(context.filesDir, "podcasts").apply { mkdirs() }
            val outputFile = File(outputDir, "${UUID.randomUUID()}.wav")
            val result = tts.synthesizeDialogue(
                script = script,
                voices = GeminiTts.VoicePair.Default,
                outputFile = outputFile,
            )

            // 3. Persist
            podcastDao.update(
                podcastDao.get(id)!!.copy(
                    filePath = result.file.absolutePath,
                    durationMs = result.durationMs,
                    status = PodcastGenerationStatus.READY.name,
                    generationError = null,
                )
            )
            Log.i(TAG, "podcast $id ready (${result.bytes / 1024}KB, ${result.durationMs / 1000}s)")
        }.onFailure { t ->
            podcastDao.update(
                podcastDao.get(id)!!.copy(
                    status = PodcastGenerationStatus.FAILED.name,
                    generationError = t.message ?: "unknown",
                )
            )
            Log.e(TAG, "podcast $id failed", t)
        }

        return id
    }

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
no stage directions.

Rules:
 - Open with the Host briefly introducing the company and the quarter
 - Get to the numbers quickly: revenue, EPS, guidance, segments
 - Surface the bull and bear cases via direct questions
 - Use natural conversational rhythm (5-15 turns total, max ~3500 words)
 - When the analyst quotes a number, do so verbally ("about forty-four billion")
   alongside the digits — TTS reads digits fine but spoken numbers feel better
 - End with the Host naming the next catalyst to watch
 - No filler ("That's a great question"). Get to substance immediately.
"""
    }
}
