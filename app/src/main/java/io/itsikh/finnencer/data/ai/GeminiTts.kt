package io.itsikh.finnencer.data.ai

import android.util.Base64
import android.util.Log
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
    suspend fun synthesizeDialogue(
        script: String,
        voices: VoicePair = VoicePair.Default,
        outputFile: File,
        model: String = "gemini-2.5-flash-preview-tts",
    ): TtsResult {
        val chunks = chunkAtSpeakerBoundaries(script, CHARS_PER_CHUNK)
        if (chunks.isEmpty()) error("Empty script")

        val pcmStream = ByteArrayOutputStreamSimple()
        var charsSubmitted = 0
        val startedAt = System.currentTimeMillis()

        for ((idx, chunk) in chunks.withIndex()) {
            val req = buildRequest(chunk, voices)
            val resp = runCatching { service.generateContent(model = model, request = req) }
                .onFailure {
                    Log.w(TAG, "tts chunk $idx failed: ${it.message}")
                }
                .getOrThrow()
            val part = resp.candidates.firstOrNull()?.content?.parts?.firstOrNull()
            val inline = part?.inlineData ?: error("Gemini returned no inlineData in chunk $idx")
            val bytes = Base64.decode(inline.data, Base64.DEFAULT)
            // inlineData is raw PCM (audio/L16). Append directly; we'll wrap
            // a WAV header around the whole thing once at the end.
            pcmStream.write(bytes)
            charsSubmitted += chunk.length
            Log.i(TAG, "tts chunk ${idx + 1}/${chunks.size} ok (${bytes.size}B)")
        }

        val pcmBytes = pcmStream.toByteArray()
        writeWav(pcmBytes = pcmBytes, sampleRateHz = SAMPLE_RATE, outputFile = outputFile)

        val durationMs = (pcmBytes.size / (SAMPLE_RATE * BYTES_PER_SAMPLE)) * 1000L
        recordUsage(model, charsSubmitted, pcmBytes.size, startedAt, ok = true, err = null)
        return TtsResult(
            file = outputFile,
            durationMs = durationMs,
            bytes = outputFile.length(),
            chunks = chunks.size,
        )
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

    private fun writeWav(pcmBytes: ByteArray, sampleRateHz: Int, outputFile: File) {
        outputFile.parentFile?.mkdirs()
        FileOutputStream(outputFile).use { fos ->
            val totalDataLen = pcmBytes.size + 36
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
                    (pcmBytes.size and 0xff).toByte(),
                    ((pcmBytes.size shr 8) and 0xff).toByte(),
                    ((pcmBytes.size shr 16) and 0xff).toByte(),
                    ((pcmBytes.size shr 24) and 0xff).toByte(),
                )
            )
            fos.write(pcmBytes)
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

    /** Tiny stand-in for ByteArrayOutputStream that ignores @Throws ceremony. */
    private class ByteArrayOutputStreamSimple {
        private val buf = java.io.ByteArrayOutputStream()
        fun write(b: ByteArray) { buf.write(b, 0, b.size) }
        fun toByteArray(): ByteArray = buf.toByteArray()
    }

    private companion object {
        const val TAG = "GeminiTts"
        const val CHARS_PER_CHUNK = 4_500
        const val SAMPLE_RATE = 24_000
        const val BYTES_PER_SAMPLE = 2 // 16-bit mono
        private fun nope(@Suppress("unused") v: Any) {
            @Suppress("UNUSED_EXPRESSION") min(0, 0)
        }
    }
}
