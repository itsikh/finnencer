package io.itsikh.finnencer.data.ai

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Second-pass quality check between podcast script generation and TTS.
 * Reads the generated script against the original requirements; either
 * PASSES the script through, FIXED-rewrites it, or FAIL-flags it as
 * unshippable for the user to review on the Task Detail screen.
 *
 * Routes through [AiUsage.PODCAST_VALIDATION] so the user can swap the
 * validator model in Settings → AI without code changes.
 */
@Singleton
class PodcastValidator @Inject constructor(
    private val router: AiRouter,
    private val promptPrefs: PromptPreferences,
) {

    enum class Verdict { PASS, FIXED, FAIL }

    data class Result(
        val verdict: Verdict,
        val script: String?,        // null when verdict == FAIL
        val notes: String,
        val model: String,
    )

    suspend fun validate(
        script: String,
        sourceBundle: String,
        targetChars: Int,
        minutes: Int,
        customPrompt: String?,
    ): Result {
        val system = promptPrefs.applyExtras(
            base = DefaultPrompts.forUsage(AiUsage.PODCAST_VALIDATION),
            extra = promptPrefs.get(AiUsage.PODCAST_VALIDATION),
            perCallCustom = customPrompt,
        )
        val user = buildString {
            append("Requirements:\n")
            append("- Target duration: ").append(minutes).append(" minutes\n")
            append("- Target character count: ").append(targetChars).append('\n')
            append("- Analyst Reactions segment required: ").append(minutes >= 20).append('\n')
            append("\nSource bundle (ground truth for facts):\n")
            append(sourceBundle)
            append("\n\nScript to validate (length=").append(script.length).append(" chars):\n")
            append(script)
        }
        // ~3.5 chars/token. Output budget = original script + headroom
        // for the validator to lengthen if needed.
        val maxTokens = ((targetChars * 1.2) / 2.5).toInt().coerceIn(3000, 16000)
        val completion = router.complete(
            usage = AiUsage.PODCAST_VALIDATION,
            system = system,
            userMessage = user,
            maxTokens = maxTokens,
            temperature = 0.2,
        )
        return parse(completion.text, modelId = completion.modelUsed.id, fallback = script)
    }

    private fun parse(raw: String, modelId: String, fallback: String): Result {
        val verdictMatch = VERDICT_RE.find(raw)
        val notesMatch = NOTES_RE.find(raw)
        val scriptSplit = raw.split(SCRIPT_DELIMITER, limit = 2)

        val verdict: Verdict = when (verdictMatch?.groupValues?.getOrNull(1)?.uppercase()) {
            "PASS" -> Verdict.PASS
            "FIXED" -> Verdict.FIXED
            "FAIL" -> Verdict.FAIL
            else -> {
                // Unparseable — treat as FAIL so the user reviews; but
                // keep the original script available via the fallback
                // so the resume-anyway path works.
                return Result(
                    verdict = Verdict.FAIL,
                    script = null,
                    notes = "Validator output was unparseable — could not extract a VERDICT line. Raw: " +
                        raw.take(400).replace('\n', ' '),
                    model = modelId,
                )
            }
        }

        val notes = notesMatch?.groupValues?.getOrNull(1)?.trim().orEmpty()
            .ifBlank { "Validator returned no notes." }

        val script: String? = when (verdict) {
            Verdict.PASS -> fallback
            Verdict.FIXED -> {
                val body = scriptSplit.getOrNull(1)?.trim()
                if (body.isNullOrBlank()) {
                    // FIXED verdict but no script body — treat as FAIL
                    // so the user reviews rather than silently dropping
                    // a partial output to TTS.
                    return Result(
                        verdict = Verdict.FAIL,
                        script = null,
                        notes = "$notes (validator returned FIXED but did not include a script body)",
                        model = modelId,
                    )
                }
                body
            }
            Verdict.FAIL -> null
        }

        return Result(verdict = verdict, script = script, notes = notes, model = modelId)
    }

    private companion object {
        val VERDICT_RE = Regex("(?m)^VERDICT:\\s*(PASS|FIXED|FAIL)\\b", RegexOption.IGNORE_CASE)
        val NOTES_RE = Regex(
            "(?ms)^NOTES:\\s*(.+?)(?=^---SCRIPT---|\\Z)",
            setOf(RegexOption.IGNORE_CASE),
        )
        const val SCRIPT_DELIMITER = "---SCRIPT---"
    }
}
