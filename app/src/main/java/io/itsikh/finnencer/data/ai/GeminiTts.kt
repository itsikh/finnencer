package io.itsikh.finnencer.data.ai

import android.util.Base64
import io.itsikh.finnencer.logging.AppLogger as Log
import io.itsikh.finnencer.data.api.GeminiContent
import io.itsikh.finnencer.data.api.GeminiGenerateRequest
import io.itsikh.finnencer.data.api.GeminiGenerationConfig
import io.itsikh.finnencer.data.api.GeminiMultiSpeakerConfig
import io.itsikh.finnencer.data.api.GeminiPart
import io.itsikh.finnencer.data.api.GeminiPrebuiltVoice
import io.itsikh.finnencer.data.api.GeminiService
import io.itsikh.finnencer.data.api.GeminiSpeakerVoiceConfig
import io.itsikh.finnencer.data.api.GeminiSpeechConfig
import io.itsikh.finnencer.data.api.GeminiVoiceConfig
import io.itsikh.finnencer.data.dao.ApiUsageDao
import io.itsikh.finnencer.data.entity.ApiUsage
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

/**
 * Wraps Gemini 2.5 Flash multi-speaker TTS, chunks long dialogue scripts,
 * synthesizes each chunk, and concatenates the resulting PCM into a single
 * 24kHz 16-bit mono WAV file.
 *
 * Each chunk is bounded by character count to keep within the model's per-
 * call comfort zone (quality drifts past a few minutes of audio).
 */
@Singleton
class GeminiTts @Inject constructor(
    private val service: GeminiService,
    private val apiUsageDao: ApiUsageDao,
    private val networkAvailability: io.itsikh.finnencer.core.net.NetworkAvailability,
) {

    /** Reasonable default voice pair: Charon (host) + Aoede (analyst). */
    data class VoicePair(val host: String, val analyst: String) {
        companion object {
            val Default = VoicePair(host = "Charon", analyst = "Aoede")
        }
    }

    /**
     * Render [script] (a dialogue with `Host:` / `Analyst:` line prefixes) to
     * a single WAV file at [outputFile]. Returns the output file size +
     * approximate duration in milliseconds.
     */
    /**
     * Render [script] to [outputFile] (24 kHz mono 16-bit WAV).
     *
     * When [cacheDir] is non-null, each chunk's decoded PCM is persisted
     * to `cacheDir/chunk_$i.pcm` immediately after Gemini returns it.
     * On a retry that calls this method with the same script + same
     * [cacheDir], chunks already on disk are reused and only the missing
     * ones are re-billed (#42 — "rewire to be bulletproof"). Caller is
     * expected to wipe [cacheDir] when starting fresh.
     */
    suspend fun synthesizeDialogue(
        script: String,
        voices: VoicePair = VoicePair.Default,
        outputFile: File,
        model: String = "gemini-2.5-flash-preview-tts",
        cacheDir: File? = null,
    ): TtsResult {
        val chunks = chunkAtSpeakerBoundaries(script, CHARS_PER_CHUNK)
        if (chunks.isEmpty()) error("Empty script")

        outputFile.parentFile?.mkdirs()
        cacheDir?.mkdirs()
        // Stream decoded PCM straight to a temp file rather than
        // accumulating in an in-memory ByteArrayOutputStream. A 10-min
        // podcast is ~60 MB of PCM across ~6 chunks; holding all of it
        // on the heap (twice, since .toByteArray() copies) triggers
        // OutOfMemoryError on real devices (#31 — Base64.decode OOM).
        val pcmTmp = File(outputFile.parentFile, "${outputFile.name}.pcm.tmp")
        runCatching { pcmTmp.delete() }
        var charsSubmitted = 0
        var pcmTotalBytes = 0L
        var cachedChunks = 0
        val startedAt = System.currentTimeMillis()

        FileOutputStream(pcmTmp).use { pcmOut ->
            for ((idx, chunk) in chunks.withIndex()) {
                val chunkFile = cacheDir?.let { File(it, "chunk_$idx.pcm") }
                val bytes: ByteArray = if (chunkFile != null && chunkFile.exists() && chunkFile.length() > 0) {
                    cachedChunks++
                    Log.i(TAG, "tts chunk ${idx + 1}/${chunks.size} reused from cache (${chunkFile.length()}B)")
                    chunkFile.readBytes()
                } else {
                    val req = buildRequest(chunk, voices)
                    val resp = generateWithRetry(model, req, idx)
                    val part = resp.candidates.firstOrNull()?.content?.parts?.firstOrNull()
                    val inline = part?.inlineData ?: error("Gemini returned no inlineData in chunk $idx")
                    val decoded = Base64.decode(inline.data, Base64.DEFAULT)
                    // Write to cache atomically (tmp then rename) so a
                    // crash mid-write can never leave a half-written
                    // chunk that resume would treat as complete.
                    if (chunkFile != null) {
                        val tmp = File(chunkFile.parentFile, "${chunkFile.name}.tmp")
                        runCatching { tmp.delete() }
                        FileOutputStream(tmp).use { it.write(decoded) }
                        if (!tmp.renameTo(chunkFile)) {
                            // Rename can fail on some FS-edge cases —
                            // fall back to copy-then-delete which is
                            // slightly less atomic but always works.
                            tmp.inputStream().use { input ->
                                FileOutputStream(chunkFile).use { input.copyTo(it) }
                            }
                            runCatching { tmp.delete() }
                        }
                    }
                    charsSubmitted += chunk.length
                    Log.i(TAG, "tts chunk ${idx + 1}/${chunks.size} ok (${decoded.size}B)")
                    decoded
                }
                pcmOut.write(bytes)
                pcmTotalBytes += bytes.size
                @Suppress("UNUSED_VALUE", "AssignedValueIsNeverRead")
                var unused: ByteArray? = bytes
                unused = null
            }
        }

        writeWavFromPcmFile(pcmTmp, pcmTotalBytes, SAMPLE_RATE, outputFile)
        runCatching { pcmTmp.delete() }
        // Once we have the final WAV the per-chunk cache is dead weight.
        cacheDir?.let { dir ->
            runCatching { dir.listFiles()?.forEach { it.delete() } ; dir.delete() }
        }

        val durationMs = (pcmTotalBytes / (SAMPLE_RATE * BYTES_PER_SAMPLE)) * 1000L
        // Only count the chunks we actually sent to Gemini against usage —
        // cached chunks were already billed on the prior attempt.
        recordUsage(model, charsSubmitted, pcmTotalBytes.toInt(), startedAt, ok = true, err = null)
        Log.i(TAG, "tts done: ${chunks.size} chunks total, $cachedChunks from cache, ${chunks.size - cachedChunks} freshly synthesized")
        return TtsResult(
            file = outputFile,
            durationMs = durationMs,
            bytes = outputFile.length(),
            chunks = chunks.size,
        )
    }

    /**
     * Call Gemini's generate-content endpoint with retry on any transient
     * network error. 10 attempts with linear 5n-second backoff (5s, 10s,
     * 15s, … 50s) — matches the podcast-script retry policy so a flaky
     * network is equally tolerated by the TTS stage (#41 follow-up).
     * Coroutine cancellation is re-thrown so cooperative cancellation
     * still works.
     */
    private suspend fun generateWithRetry(
        model: String,
        req: GeminiGenerateRequest,
        chunkIdx: Int,
    ): io.itsikh.finnencer.data.api.GeminiGenerateResponse {
        var lastErr: Throwable? = null
        for (attempt in 1..RETRY_ATTEMPTS) {
            try {
                return service.generateContent(model = model, request = req)
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (t: Throwable) {
                lastErr = t
                if (attempt >= RETRY_ATTEMPTS) break
                val backoffMs = attempt * RETRY_BACKOFF_STEP_MS // 5s, 10s, 15s, ...
                Log.w(TAG, "tts chunk $chunkIdx attempt $attempt/$RETRY_ATTEMPTS failed (${t.javaClass.simpleName}: ${t.message}); waiting up to ${backoffMs / 1000}s for network")
                // Wake the retry the moment connectivity returns rather
                // than sleeping the full backoff window blind (#42).
                networkAvailability.awaitNetwork(backoffMs)
            }
        }
        throw lastErr ?: IllegalStateException("tts chunk $chunkIdx failed after $RETRY_ATTEMPTS attempts")
    }

    private fun buildRequest(text: String, voices: VoicePair) = GeminiGenerateRequest(
        contents = listOf(GeminiContent(parts = listOf(GeminiPart(text = text)))),
        generationConfig = GeminiGenerationConfig(
            temperature = 0.6,
            responseModalities = listOf("AUDIO"),
            speechConfig = GeminiSpeechConfig(
                multiSpeakerVoiceConfig = GeminiMultiSpeakerConfig(
                    speakerVoiceConfigs = listOf(
                        GeminiSpeakerVoiceConfig(
                            speaker = "Host",
                            voiceConfig = GeminiVoiceConfig(
                                prebuiltVoiceConfig = GeminiPrebuiltVoice(voiceName = voices.host),
                            ),
                        ),
                        GeminiSpeakerVoiceConfig(
                            speaker = "Analyst",
                            voiceConfig = GeminiVoiceConfig(
                                prebuiltVoiceConfig = GeminiPrebuiltVoice(voiceName = voices.analyst),
                            ),
                        ),
                    ),
                ),
            ),
        ),
    )

    /**
     * Split [script] at Host:/Analyst: boundaries into chunks no larger
     * than [maxChars] characters. Always preserves the speaker labels.
     */
    private fun chunkAtSpeakerBoundaries(script: String, maxChars: Int): List<String> {
        val trimmed = script.trim()
        if (trimmed.length <= maxChars) return listOf(trimmed)
        val turns = trimmed
            .split(Regex("(?=^(?:Host:|Analyst:))", RegexOption.MULTILINE))
            .map { it.trim() }
            .filter { it.isNotBlank() }
        val out = ArrayList<String>()
        val buf = StringBuilder()
        for (turn in turns) {
            if (buf.length + turn.length + 2 > maxChars && buf.isNotEmpty()) {
                out += buf.toString()
                buf.setLength(0)
            }
            if (turn.length > maxChars) {
                // Single turn too long — hard split at sentence boundaries.
                val sentences = turn.split(Regex("(?<=[.!?])\\s+"))
                for (s in sentences) {
                    if (buf.length + s.length + 1 > maxChars && buf.isNotEmpty()) {
                        out += buf.toString()
                        buf.setLength(0)
                    }
                    if (buf.isNotEmpty()) buf.append(' ')
                    buf.append(s)
                }
            } else {
                if (buf.isNotEmpty()) buf.append("\n\n")
                buf.append(turn)
            }
        }
        if (buf.isNotEmpty()) out += buf.toString()
        return out
    }

    /**
     * Stream a WAV file: write the 44-byte header, then copy PCM bytes
     * from [pcmFile] directly to [outputFile]. Never loads the full PCM
     * into memory.
     */
    private fun writeWavFromPcmFile(
        pcmFile: File,
        pcmSizeBytes: Long,
        sampleRateHz: Int,
        outputFile: File,
    ) {
        outputFile.parentFile?.mkdirs()
        val pcmSize = pcmSizeBytes.toInt() // WAV header is 32-bit
        FileOutputStream(outputFile).use { fos ->
            val totalDataLen = pcmSize + 36
            val byteRate = sampleRateHz * BYTES_PER_SAMPLE
            fos.write(
                byteArrayOf(
                    'R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte(),
                    (totalDataLen and 0xff).toByte(),
                    ((totalDataLen shr 8) and 0xff).toByte(),
                    ((totalDataLen shr 16) and 0xff).toByte(),
                    ((totalDataLen shr 24) and 0xff).toByte(),
                    'W'.code.toByte(), 'A'.code.toByte(), 'V'.code.toByte(), 'E'.code.toByte(),
                    'f'.code.toByte(), 'm'.code.toByte(), 't'.code.toByte(), ' '.code.toByte(),
                    16, 0, 0, 0,
                    1, 0,        // PCM
                    1, 0,        // mono
                    (sampleRateHz and 0xff).toByte(),
                    ((sampleRateHz shr 8) and 0xff).toByte(),
                    ((sampleRateHz shr 16) and 0xff).toByte(),
                    ((sampleRateHz shr 24) and 0xff).toByte(),
                    (byteRate and 0xff).toByte(),
                    ((byteRate shr 8) and 0xff).toByte(),
                    ((byteRate shr 16) and 0xff).toByte(),
                    ((byteRate shr 24) and 0xff).toByte(),
                    2, 0,        // block align (mono * 16/8)
                    16, 0,       // bits per sample
                    'd'.code.toByte(), 'a'.code.toByte(), 't'.code.toByte(), 'a'.code.toByte(),
                    (pcmSize and 0xff).toByte(),
                    ((pcmSize shr 8) and 0xff).toByte(),
                    ((pcmSize shr 16) and 0xff).toByte(),
                    ((pcmSize shr 24) and 0xff).toByte(),
                )
            )
            FileInputStream(pcmFile).use { fis ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = fis.read(buf)
                    if (n <= 0) break
                    fos.write(buf, 0, n)
                }
            }
        }
    }

    private suspend fun recordUsage(
        model: String,
        charsIn: Int,
        bytesOut: Int,
        startedAt: Long,
        ok: Boolean,
        err: String?,
    ) {
        // Rough cost: $0.30/M input tokens (~chars/4) + $2.50/M audio out
        // tokens (~bytes/2/24 for L16 24kHz mono).
        val inTokens = charsIn / 4
        val outTokens = (bytesOut / 2) / 24
        val inCents = (inTokens / 1_000_000.0) * 0.30 * 100
        val outCents = (outTokens / 1_000_000.0) * 2.50 * 100
        val millicents = ((inCents + outCents) * 1000).toLong()
        apiUsageDao.insert(
            ApiUsage(
                provider = "Gemini TTS",
                endpoint = "generateContent [$model]",
                inputTokens = inTokens,
                outputTokens = outTokens,
                characterCount = charsIn,
                costMillicents = millicents,
                requestedAtMillis = startedAt,
                ok = ok,
                errorMessage = err,
            )
        )
    }

    data class TtsResult(
        val file: File,
        val durationMs: Long,
        val bytes: Long,
        val chunks: Int,
    )

    private companion object {
        const val TAG = "GeminiTts"
        const val CHARS_PER_CHUNK = 4_500
        const val SAMPLE_RATE = 24_000
        const val BYTES_PER_SAMPLE = 2 // 16-bit mono
        const val RETRY_ATTEMPTS = 10
        const val RETRY_BACKOFF_STEP_MS = 5_000L
        private fun nope(@Suppress("unused") v: Any) {
            @Suppress("UNUSED_EXPRESSION") min(0, 0)
        }
    }
}
