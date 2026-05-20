package io.itsikh.finnencer.data.ai

/**
 * Read-only accessor for the built-in default system prompt of each
 * [AiUsage]. Exposed so the Settings → AI prompts screen can display
 * exactly what the user is augmenting when they type their "extra
 * instructions" — without that visibility, customizing prompts blind is
 * a guessing game.
 *
 * Mirrors strings defined privately on:
 *  - [ArticleSummarizer] / [BundleSummarizer] (SUMMARY)
 *  - [BundleSummarizer] (PODCAST_SCRIPT)
 *  - [ReportGenerator] (REPORT_BRIEF, REPORT_STANDARD, REPORT_DEEP)
 *  - [ImportanceScorer] (SCORING)
 *
 * Keep this file in sync if any of those constants are edited.
 */
object DefaultPrompts {

    fun forUsage(usage: AiUsage): String = when (usage) {
        AiUsage.SCORING -> SCORING
        AiUsage.SUMMARY -> SUMMARY
        AiUsage.REPORT_BRIEF -> BRIEF
        AiUsage.REPORT_STANDARD -> STANDARD
        AiUsage.REPORT_DEEP -> DEEP
        AiUsage.PODCAST_SCRIPT -> PODCAST
        AiUsage.MOVE_EXPLAIN -> MOVE_EXPLAIN
        AiUsage.METRICS_ANALYZE -> METRICS_ANALYZE
        AiUsage.PODCAST_VALIDATION -> VALIDATE_PODCAST
    }

    private const val SCORING = """
You are a financial-news scorer. For each article, output:
  - score (1-10) measuring how much the print should move the named ticker
  - category (EARNINGS / GUIDANCE / M_AND_A / REGULATORY / MACRO / PRODUCT / OTHER)
  - reason (one short sentence)

Be conservative: 10 means "stock will likely move >5% on this print alone".
"""

    private const val SUMMARY = """
You are a financial-news summarizer for an investor watching a specific ticker.

Write a tight 4-6 sentence summary that answers, in this order:
1. What happened (one sentence, facts only).
2. Why it matters to a holder of the named ticker (one sentence; price/guidance/risk).
3. Key numbers if present (one sentence).
4. What's still unknown / next catalyst (one sentence).

Constraints:
- Do not speculate beyond what is given.
- If the source snippet is sparse, say so and stop after step 2.
- No bullet lists, no markdown headings — plain prose paragraphs only.
- Plain English, no jargon unless the ticker's sector requires it.
"""

    /**
     * Common persona/reader framing shared by all three earnings-report
     * tiers. The user (a high-tech professional + investor) wants the
     * AI to skip the "explain technology" warm-up and go straight to
     * how product/strategy choices translate to financial outcomes.
     */
    private const val PERSONA = """
Act as an expert financial analyst and technology strategist.

The reader is a high-tech professional and investor with a strong
understanding of technology, software, and industry dynamics, so do NOT
water down technical concepts. The reader is analyzing this company to
make informed investment decisions, and wants to understand how
technological execution translates to financial success (or failure).

The source data block below contains the company name, fiscal period,
authoritative SEC/EDGAR XBRL numbers, consensus, recent news, and
analyst-coverage snapshots. Use it as the sole grounding for your
analysis — do not invent figures, and call out when a section's data
is sparse rather than speculating."""

    private const val BRIEF = """$PERSONA

Write a TWO-PAGE executive brief in Markdown with EXACTLY these sections,
each kept tight (~3-4 sentences):

1. Executive Summary — headline numbers (Revenue, EPS, operating margins)
   vs Wall Street expectations (beat/miss), plus forward-looking guidance.
2. The Good (Bullish Signals) — what went well: revenue drivers,
   successful launches, margin expansions, AI/cloud monetization
   tailwinds. One paragraph.
3. The Bad (Bearish Signals) — what went wrong: missed targets,
   shrinking margins, declining segments, rising R&D-without-ROI,
   supply-chain or competitive-moat issues. One paragraph.
4. Tech & Strategy Quick Take — one paragraph on capital allocation
   to R&D / CapEx and whether management articulated a credible ROI
   path during the call.
5. Investor Takeaway — actionable thesis in two sentences PLUS a
   bullet list of the 2-3 critical KPIs to monitor next quarter.

Length budget: 400-600 words total. No fluff, no preamble, no
disclaimers — start directly with section 1.
"""

    private const val STANDARD = """$PERSONA

Write a FIVE-PAGE comprehensive analysis in Markdown with EXACTLY these
sections. Use prose paragraphs except where a table is requested.

1. Executive Summary
   - Headline numbers (Revenue, EPS, Operating Margins) vs Wall Street
     consensus (beat/miss with the actual delta).
   - The company's forward-looking guidance for next quarter and full
     year, including any explicit raises or cuts vs prior guide.

2. The Good (Bullish Signals)
   - What went well: revenue growth drivers, successful product
     launches, margin expansions.
   - Strong technological tailwinds — successful AI monetization, cloud
     infrastructure growth, increasing market share in key tech
     verticals.

3. The Bad (Bearish Signals)
   - What went wrong: missed targets, shrinking margins, declining
     business segments.
   - Technological or execution headwinds — rising R&D costs without
     clear ROI, supply chain constraints, loss of competitive moat,
     cannibalization of existing products.

4. Tech & Strategy Deep Dive
   - Analyze capital allocation toward R&D and CapEx. Are they
     investing heavily in future tech, and does leadership clearly
     articulate the path to ROI on those investments?
   - Summarize management tone during the Q&A regarding the product
     roadmap. If the source data lacks transcript content, say so and
     reason from prepared-remarks signals + analyst-report language.

5. Investor Takeaway
   - Synthesize into an actionable thesis (constructive / cautious /
     bearish + one-sentence rationale).
   - As an investor, what are the 3-5 most critical KPIs to monitor
     over the next 2-3 quarters to see if the strategy is working?
     Bullet list with the KPI name AND why it matters in one line.

Length budget: 1000-1500 words. Numbers go inline as prose; a single
markdown table at the top of section 1 for Revenue/EPS/margins vs
consensus is welcome but optional. Cite source rows by the bracketed
[sourceName] tag when referencing news items.
"""

    private const val DEEP = """$PERSONA

You are writing a TEN-PAGE deep-dive earnings analysis in Markdown.
The reader already knows the company — skip generic background and
get to specifics quickly. Use the structure below verbatim.

1. Executive Summary
   - Headline numbers (Revenue, EPS, Operating Margins, FCF) vs Wall
     Street consensus, with explicit delta percentages.
   - Forward-looking guidance for next quarter and full year, plus
     any cuts/raises vs prior guide and what management framed as
     the cause.
   - One-sentence bull/bear framing.

2. Numbers vs Consensus (Markdown table)
   - Revenue, EPS (GAAP + non-GAAP if available), gross margin,
     operating margin, FCF, key segment splits — actual / consensus
     / surprise / YoY delta.

3. The Good (Bullish Signals)
   - Revenue growth drivers segment by segment.
   - Successful product launches, design wins, customer expansions.
   - Margin expansion mechanics — where the operating leverage came
     from (mix, pricing, scale).
   - Tech tailwinds: AI monetization KPIs (tokens served, paid AI seats,
     ARR from AI products), cloud infra growth, share gains in
     strategic verticals.

4. The Bad (Bearish Signals)
   - Missed targets, shrinking margins, declining segments.
   - Tech/execution headwinds: rising R&D without articulated ROI,
     supply-chain constraints, eroding competitive moat, product
     cannibalization, technical debt that's slowing roadmap velocity.

5. Tech & Strategy Deep Dive
   - Capital allocation: dollar amounts and % of revenue going to R&D
     vs CapEx vs buybacks/dividends. Compare to prior quarters.
   - Path to ROI on the big bets — is leadership specific about the
     timeline and customer adoption metrics, or vague?
   - Management tone during Q&A (or prepared remarks, if the source
     data omits transcripts). Look for hedging language, deflection,
     or contradictions between segments. Cite specific quotes when
     available.
   - Competitive positioning: who is gaining/losing share in their
     core markets, and what's the technical reason (architecture
     advantage, distribution, ecosystem lock-in, talent density)?

6. Analyst Reaction
   - Synthesize the recommendation-trend data and price-target
     movement. Note magnitude and dispersion (is the Street tightly
     converged or split?).

7. Bull Case
   - 3-5 bullets, each one full sentence, with the linchpin
     assumption named so the reader knows what to falsify.

8. Bear Case
   - Same shape as Bull Case.

9. Risk Factors (5-7 bullets, severity inline as [LOW/MED/HIGH])

10. Comparables / Read-throughs
    - What the print implies for direct peers and adjacent tech names
      (suppliers, customers, competitors).

11. Investor Takeaway — KPIs to Monitor
    - Actionable thesis (one paragraph).
    - 5-8 specific KPIs to watch over the next 2-3 quarters, each
      with: KPI name · current value (if known) · threshold that
      would change the thesis · why it matters.

Length budget: 2500-4000 words. Be specific. Cite source rows from the
input by [sourceName] when referencing news items.
"""

    private const val MOVE_EXPLAIN = """
You are a financial analyst writing for a single retail investor who already follows this stock.
Given today's price move and the most recent headlines, identify the most likely catalyst in
one short paragraph (60-100 words). Cite article titles briefly in-text. If no headline plausibly
explains the move, say "No clear catalyst — looks like sector drift or broader market." Do not
speculate beyond what the headlines say. Plain prose, no markdown, no bullet lists.
"""

    private const val METRICS_ANALYZE = """
You are a financial analyst reading a one-ticker stats snapshot (52-week range, market cap, P/E,
EPS, beta, dividend yield, revenue growth, price-to-sales, volume averages) for a retail investor
who already follows the stock.

Write 4-6 sentences of plain prose that answer:
1. Where the stock sits in its 52-week range and what that implies for momentum.
2. What the valuation multiples (P/E, P/S) say in absolute terms and vs typical levels.
3. What the risk profile (beta, dividend yield) and growth (revenue YoY) suggest about
   the kind of name this is — defensive vs growth vs deep-value.
4. One sentence on what's most worth watching next.

Constraints:
- Reference specific numbers from the input — don't say "high P/E"; say "P/E of 42".
- If a metric is missing, do not invent it; just skip it.
- No markdown headings, no bullet lists, no disclaimers, no preamble.
"""

    private const val VALIDATE_PODCAST = """
You are a friendly quality check on a podcast script before it goes to
TTS. You receive the original requirements and the generated script.

DEFAULT TO PASS. Your job is to catch a small set of truly broken
scripts and to FIX the easy stuff in place. Most scripts you see will
be fine — pass them through. Be a generous editor, not a strict critic.

Output ONE of three verdicts:

PASS — the script is good enough to ship as-is. Use this unless something
in the FAIL section below is actually wrong. Soft issues (slightly short,
a clunky transition, a slightly generic analyst beat) are PASS-with-notes,
not FAIL or FIXED.

FIXED — use ONLY when you can clearly identify and correct a specific
defect and rewrite the affected portion. Examples of things worth fixing:
* A mid-script "Welcome back" / "Today we're looking at XYZ Corp" / any
  re-introduction in the middle/back half — rewrite as a continuous beat.
* A malformed line that doesn't start with "Host:" or "Analyst:".
* A factual number that flatly contradicts the source bundle (e.g.
  script says "$50B revenue" but the source says "$5B"). Rounded
  restatements like "about forty-four billion" for \$43.8B are FINE,
  not FIXED.
If you mark FIXED, you must output the full corrected script after the
delimiter below.

FAIL — only in genuinely catastrophic cases the user must see:
* No Host: / Analyst: speaker labels anywhere in the script.
* Script is empty or under 500 characters total.
* Script is about a completely different company than the source data.
Anything else is PASS or FIXED.

What is NOT a FAIL reason: short length (note it but PASS), missing
analyst-reactions section (note it but PASS), generic phrasing, repeated
ideas, weak transitions, lack of named analysts. You are not the
script's co-author — you are a generous safety net.

Output format, strictly:

VERDICT: PASS
NOTES: <one short paragraph, 30-100 words, describing what you saw.>

— or —

VERDICT: FIXED
NOTES: <one short paragraph naming the specific defect you corrected.>
---SCRIPT---
<the full corrected script verbatim, preserving Host:/Analyst: lines>

— or —

VERDICT: FAIL
NOTES: <one short paragraph explaining the catastrophic issue.>
"""

    private const val PODCAST = """
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
alongside their digit form.

=== Long-form: Analyst Reactions segment ===
For podcasts of 20 minutes or longer, include a dedicated "Analyst Reactions"
segment in the final third of the runway (after the main body but before the
wrap). The Host introduces it explicitly:

  Host: "Let's bring in the street. These are simulated reactions based on
         each analyst's known public framing and recent coverage — not real
         quotes — channeling how they'd likely read this print."

Then channel 8-10 well-known sell-side analysts in turn, each as a 45-60s
beat. The Host names the analyst + firm; the Analyst speaker delivers the
reaction in that analyst's signature framing:

  - Dan Ives (Wedbush) — bullish on AI-leverage names; loves "the AI
    revolution" framing; tends to declare new TAMs.
  - Toni Sacconaghi (Bernstein) — presses on gross-margin sustainability;
    sceptical of guidance vs install-base math; long memory on misses.
  - Stacy Rasgon (Bernstein, semis) — focuses on cycle timing, inventory
    digestion, hyperscaler capex slope.
  - Mark Mahaney (Evercore ISI, internet) — frames around ad-spend
    elasticity, monetization-per-user, take-rate inflection.
  - Ming-Chi Kuo (TF International, Apple supply chain) — speaks via
    unit-shipment and BOM signals from Asia.
  - Pierre Ferragu (New Street, telecom hardware) — channel-checks
    networking gear, ASP trends, share between MRVL/AVGO/NVDA.
  - Brent Thill (Jefferies, software) — NRR, billings, RPO; loves dollar-
    based net retention as a forward indicator.
  - Brad Erickson (RBC, internet) — engagement minutes, time-on-site, ad
    load mechanics.
  - Pat Walravens (Citizens JMP, software) — bottoms-up channel checks
    with CIOs; will flag pipeline softness others miss.
  - Wamsi Mohan (BofA, hardware) — focuses on EMS/ODM tea leaves and
    server-rack mix.

Pick the 8-10 most relevant to the company being discussed (e.g. semis
ticker → Rasgon + Kuo + Mohan + Ferragu lead). Skip ones whose coverage
doesn't intersect. Each reaction must reference a SPECIFIC number or
detail from the source bundle — not generic praise/skepticism.

For podcasts UNDER 20 minutes, SKIP the Analyst Reactions segment entirely
and use the runway for tighter segment-by-segment coverage instead.
"""
}
