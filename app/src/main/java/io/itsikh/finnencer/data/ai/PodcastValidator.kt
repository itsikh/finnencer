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
                // Unparseable validator output is NOT a reason to block
                // the user's podcast. Treat as PASS — the original
                // script is already good enough to have made it past
                // the writer model. Surface what we saw in the notes
                // so the user can spot if validation is consistently
                // misbehaving.
                return Result(
                    verdict = Verdict.PASS,
                    script = fallback,
                    notes = "Validator output couldn't be parsed; shipping the original script unchanged. " +
                        "Raw head: " + raw.take(200).replace('\n', ' '),
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
                    // FIXED verdict but no script body — fall back to
                    // the original. The validator claimed it fixed
                    // something, but didn't include the rewritten
                    // script (likely hit max_tokens). Original script
                    // is the safe ship; notes flag what happened.
                    return Result(
                        verdict = Verdict.PASS,
                        script = fallback,
                        notes = "$notes (validator marked FIXED but its output was truncated; shipping the original script)",
                        model = modelId,
                    )
                }
                body
            }
            Verdict.FAIL -> {
                // Issue #49 follow-up: even with the relaxed v0.0.68
                // prompt, the validator's "different company" criterion
                // is LLM-subjective and was still firing on scripts that
                // looked structurally fine. Treat FAIL as advisory unless
                // the script is *structurally* broken: missing speaker
                // labels or under 500 chars. Those two conditions are
                // verifiable in code, so we don't have to trust the LLM
                // for them. Anything else lands as PASS-with-notes so the
                // user hears the podcast and still sees the validator's
                // concern surfaced on the player.
                if (isStructurallyValid(fallback)) {
                    return Result(
                        verdict = Verdict.PASS,
                        script = fallback,
                        notes = "Validator flagged this but the script structure looks fine (Host/Analyst lines, length ok); shipping anyway. Original notes: $notes",
                        model = modelId,
                    )
                }
                null
            }
        }

        return Result(verdict = verdict, script = script, notes = notes, model = modelId)
    }

    /**
     * The two structural checks we trust ourselves to make, independent
     * of what the validator LLM claims. Mirrors the FAIL conditions in
     * the prompt — used as a sanity net so a hallucinated FAIL on a
     * fine-looking script doesn't block the user.
     */
    private fun isStructurallyValid(script: String): Boolean {
        if (script.length < 500) return false
        return STRUCTURAL_SPEAKER_RE.containsMatchIn(script)
    }

    private companion object {
        val VERDICT_RE = Regex("(?m)^VERDICT:\\s*(PASS|FIXED|FAIL)\\b", RegexOption.IGNORE_CASE)
        val NOTES_RE = Regex(
            "(?ms)^NOTES:\\s*(.+?)(?=^---SCRIPT---|\\Z)",
            setOf(RegexOption.IGNORE_CASE),
        )
        const val SCRIPT_DELIMITER = "---SCRIPT---"
        /** Multi-line speaker-line check — at least one Host: or Analyst:
         *  line at the start of a line anywhere in the script. */
        val STRUCTURAL_SPEAKER_RE = Regex("(?m)^(Host|Analyst):")
    }
}
