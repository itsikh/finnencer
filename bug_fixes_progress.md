# Bug-fix progress — 2026-07-27 audit

**STATUS: SHIPPED as v0.4.0** (commit 11d95b7 + release dac08fc, tag v0.4.0,
GitHub release with APK published, issues #72–#84 auto-closed, issue tracker
at zero open). Remaining: on-device testing of the big flows.

Resume context: Claude Code session `cfcf2d70-f5bc-4990-b18a-92b7b091615d`
(`claude --resume cfcf2d70-f5bc-4990-b18a-92b7b091615d` from ~/dev/finnencer).

Source: 4-agent bug audit (podcast/AI pipeline, workers/scheduling,
news/notifications, playback/UI) — 35 verified findings: 5 high / 12 med / 18 low.
Full details live in the session transcript; each item below carries its anchor.

## Pre-existing uncommitted work (from earlier in this session, approved, NOT yet committed)

- GA Vertex TTS models (`gemini-2.5-pro-tts`, `gemini-2.5-flash-tts`) added to
  `TtsModel` picker (`PodcastPreferences.kt`), Vertex-only, excluded from fallback chain.
- NotebookLM-style dialogue prompt (`DefaultPrompts.DIALOGUE_STYLE`) + audio tags;
  `stripAudioTags()` in `GeminiTts` for non-3.1 models.
- Drift fix: `BundleSummarizer` now reads the podcast prompt from `DefaultPrompts`
  (Analyst Reactions block previously never reached runtime).

## Smoothness batch — fix now (approved 2026-07-27)

- [x] 1. Podcast pipeline deadlock: cancelled waiter now removes itself / re-hands-off
      the permit; `withPermit` release wrapped in NonCancellable (`JobConcurrencyGate.kt`).
- [x] 2. TTS failure reported as success: `renderPodcast`/`generateFromReport` now
      rethrow after marking the row FAILED (worker marks job FAILED + notifies failure);
      failure-path `podcastDao.get(id)!!` made null-safe.
- [x] 3. Chunk-resume cache corruption: cache dir now guarded by `cache.key` =
      SHA-256(model|chunkChars|voices|script); mismatch wipes stale chunks (`GeminiTts.kt`).
- [x] 4. 429 Retry-After dead code: `extractRetryAfterMs` walks the cause chain to find
      the wrapped `HttpException`.
- [x] 5. Player restart + position: `attemptLoad` adopts a live session via mediaId
      instead of re-setting the item; resumes from `playPositionMs`; position persisted
      every ~5s while playing, on pause, and reset to 0 on STATE_ENDED. Bonus: seek/skip
      update UI immediately; skipForward no longer seeks to 0 while duration is UNSET.
- [x] 6. Audio focus (`AUDIO_CONTENT_TYPE_SPEECH`/`USAGE_MEDIA`, handleAudioFocus=true)
      + `setHandleAudioBecomingNoisy(true)` on the service player.
- [x] 7. Quote poller: `onDispose` now calls `stopQuotePolling()`.
- [x] 8. Morning Brief: startup uses `ensureScheduled()` (KEEP); worker self-reschedule
      uses APPEND_OR_REPLACE; settings changes keep REPLACE.
- [x] 9. NewsSyncEngine links xrefs for ALL fetched uniques (linkTickers is IGNORE).
- [x] 10. `FeedParser` RFC822 formatter pinned to `Locale.US`.
- [x] 11. GA models: hidden in picker unless Vertex provider active; `GeminiTts` coerces
      Vertex-only ids to 3.1 Flash on the Generative Language surface. Also: strip-tags
      regex narrowed to the 9 sanctioned tags.

Batch verified: `:app:compileDebugKotlin` BUILD SUCCESSFUL. Not yet committed.

## To file as GitHub issues (not in this batch)

Mediums: EDGAR 8-K cluster-key collision suppresses other companies' filings
(`EdgarFilingsProvider.kt:50`, `NotificationDao.kt:27-36`); CompanyMatcher substring
false pos/neg (`MarketFirehoseProviders.kt:129-153`); Google News cluster key includes
"- Source" suffix (`WebNewsProviders.kt:49/56`); retention prune skipped without
Anthropic key (`SyncWorker.kt:73-89`); `.pcm.tmp` leak on failed synthesis
(`GeminiTts.kt:207/258`); missing-file podcast shows live no-op controls
(`PodcastPlayerViewModel.kt:201`, `PodcastPlayerScreen.kt:332`); deleting queued
podcast strands auto-advance on "Loading…" (`PodcastLibraryScreen.kt:93-115`);
seek-while-paused no feedback + drag seek-storm (`PodcastPlayerViewModel.kt:262`,
`PodcastPlayerScreen.kt:369`); MediaController leak / ghost playback on quick exit
(`PodcastPlayerViewModel.kt:98-107`); shared-session listener marks wrong podcast done
(`PodcastPlayerViewModel.kt:114-163`).

Lows (group into area issues): quiet-hours drops instead of defers
(`AlertNotifier.kt:98`); unscored articles retried forever (`ImportanceScorer.kt:43`);
notification-id hashCode collisions + misleading suppression stats (`AlertNotifier.kt`);
firehose failure cached as empty feed (`MarketFirehoseProviders.kt:76`); brief worker
self-cancel race (`MorningBriefWorker.kt:57` → APPEND_OR_REPLACE); non-unique AiJob
enqueue double-bills retry (`AiJobsRepository.kt:196`); failing sync pins spinner
(`SyncScheduler.kt:98`); 4xx retried as transient in TTS loop (`GeminiTts.kt:335`);
`podcastDao.get(id)!!` NPEs (`BundleSummarizer.kt`, `PodcastGenerator.kt`); +30s during
buffering seeks to 0 (`PodcastPlayerViewModel.kt:267`); replay-to-end never re-advances
(`endHandled` latch); unguarded `valueOf` crash (`TasksScreen.kt:263`); stale
earnings/news windows (`WatchlistViewModel.kt:119/158`); stripAudioTags whitelist +
Pro billed at Flash rates (`GeminiTts.kt:295/644`).

- [x] Filed on GitHub 2026-07-27:
      #72 EDGAR cluster-key collision · #73 CompanyMatcher substring matching ·
      #74 Google News cluster key suffix · #75 retention prune skipped w/o Anthropic key ·
      #76 .pcm.tmp leak · #77 missing-file podcast no-op controls ·
      #78 deleted queued podcast strands auto-advance · #79 slider seek storm ·
      #80 MediaController leak/ghost playback · #81 wrong-podcast STATE_ENDED ·
      #82 alerts/notifications lows (grouped) · #83 background-job lows (grouped) ·
      #84 UI-polish lows (grouped)

## Round 2 — "fix all" (approved 2026-07-27, all APPLIED, compile green)

All of #72–#84 fixed in the working tree (leave issues open so the commit
messages can close them):

- #72 `ArticleIds.scopedClusterKey(scope, title)`; EDGAR keys now ticker-scoped.
- #73 `CompanyMatcher` rewritten: word-boundary regexes + suffix-stripped alias
      (inc/corp/co/com/platforms/motor/…), single-word-alias stoplist; $TICKER
      matching unchanged (`MarketFirehoseProviders.kt`).
- #74 Google News clusterKey computed from the trimmed headline.
- #75 SyncWorker: retention prune runs even without an Anthropic key.
- #76 GeminiTts: pcmTmp deleted in finally + age-gated sweep of stale *.pcm.tmp.
- #77 Player: `fileMissing` state + Regenerate button instead of dead controls.
- #78 Library delete/clearAllFailed remove queue items; auto-advance skips
      dead refIds; player shows "Podcast not found" after 2s instead of
      endless Loading.
- #79 Slider: local drag state + single seek on onValueChangeFinished.
- #80 Controller-connect callback releases immediately if the VM was cleared.
- #81 STATE_ENDED handling gated on mediaId == this podcast.
- #82 AlertNotifier: quiet-hours alerts now DEFERRED via new
      `NewsDao.unnotifiedAlertCandidates` (12h lookback, same gates, no schema
      change); notify(tag=articleId, fixed id); `suppressedBySystemDisabled`
      stat; firehose failures no longer cached as empty feeds;
      `unscoredJoined` bounded to 48h so omitted articles aren't re-billed forever.
- #83 AiJob enqueues unique ("ai-job:<id>", KEEP); sync retries capped at 3
      (spinner can't pin); TTS 4xx fails fast; `!!` row fetches →
      `requirePodcastRow()`; Pro TTS billed at Pro-tier rates.
- #84 endHandled re-arms when leaving STATE_ENDED; TasksScreen valueOf guarded;
      Watchlist 'now' refreshes on a 15-min ticker.

Stale May issues #55/#57/#60/#61/#63/#64 closed as test artifacts.
Verified: `:app:compileDebugKotlin` BUILD SUCCESSFUL (Room KSP validated the
new DAO queries). NOTHING COMMITTED — tree now holds: TTS/prompt feature +
smoothness batch + round-2 fixes.

## Model migration — latest models on all AI calls (APPLIED 2026-07-27, compile green)

- Claude: `claude-sonnet-4-6` → `claude-sonnet-5`; `claude-opus-4-7` → `claude-opus-5`
  (both supportsTemperature=false; ClaudeClient sends `thinking:{"type":"disabled"}`
  on 5-family models because thinking is otherwise ON by default and max_tokens caps
  thinking+text). Haiku 4.5 stays (still newest Haiku). 1M-ctx beta header removed
  (default on 5-family).
- Gemini text: `gemini-2.5-flash` → `gemini-3.6-flash`; `gemini-2.5-pro` → `gemini-3.1-pro`.
- Token budgets +~30% for the Sonnet 5 tokenizer: report caps 2000/4600/8500,
  Pages 2600/6000/12000, script divisor /2.0 cap 14000, PodcastGenerator 6000,
  ModelCost typicalProfile rows.
- Pricing corrected everywhere (audit finding #2/#4): Opus $5/$25 (was $15/$75 — 3x
  overstatement), Gemini Pro $2/$12, Gemini Flash $0.30/$2.50, GeminiTextClient
  actuals now match ModelCost hints.
- Bonus: Opus 5 prompt-cache minimum is 512 tokens (was 2048 on 4.7) → the DEEP
  report cached-system block now actually caches (audit finding #6 partially self-fixes).
- Old stored per-usage model prefs with retired ids fall back to the new defaults
  automatically (AiPreferences.byId → null → defaultModel).

## Round 2 fixes — ALL APPLIED (2026-07-27, assembleDebug green)

All 36 round-2 findings fixed across 4 workstreams:
- Security/repos: REPLACE-cascade wipe (insertIgnore + dup guard + safe restore),
  QR black-on-white, PBKDF2 iter clamp + off-main, Finnhub header auth,
  TickerDao.updateCik everywhere (NewsSyncEngine/EarningsCalendarSync/ReportGenerator),
  backup parse-then-apply + alias whitelist + version check + refreshConfigured(),
  Zip-Slip guard, QR camera/scanner lifecycle.
- DB/nav: fallbackToDestructiveMigrationFrom(1,2,3), fiscal labels gated on the whole
  ticker-feed path, QuotePoller (parse isolation, CancellationException, backoff,
  zero-guards), earnings dedupe prefers fiscalConfirmed, queue reorder Mutex + atomic
  updateAll, nowTicker in Earnings/TickerFeed VMs, insider ticker deep link end-to-end,
  bug-reporter title sanitization + wider token patterns.
- AI clients: Gemini maxOutputTokens actually sent, string-aware extractJson,
  truncated-script trim + NonCancellable persists in PodcastGenerator, report
  truncation marker, empty-response diagnostics + usage row, EDGAR UA fallback with
  contact URL + ConcurrentHashMap cache, WavToM4a pad-byte/fmt guards.
- Mine: DeferredAlertStore (quiet-hours-only deferral registry — fixes the round-1
  regression; other suppressions terminal), fanout log-insert runCatching,
  ENDED replay seekTo(0), persist paused seeks, slider zero-duration guard,
  Vertex 401 invalidate+retry-once + permanent-config fast-fail, strip empty
  speaker turns, TtsModel.coercedFor() in smoke/status/settings + provider-switch
  reset, ClaudeModels constants → 5-family.

## Round 2 audit — original findings (all fixed above)

Agents: regression-hunt (8 findings), repos/security (9), AI-clients (11), DB/nav (8).
Highlights: TickerDao REPLACE cascade wipe (HIGH); white-on-white QR (HIGH); missing
DB migrations 1→4 crash-loop (HIGH); AlertNotifier deferral re-fires suppressed
alerts (HIGH — regression from round-1 fix, needs suppression-verdict scoping);
Gemini maxOutputTokens never sent; Vertex 401 dead code; GA-model coercion desync
(smoke test/status/picker); ENDED-session adopt leaves dead play button; QuotePoller
parse can kill poll loop; earnings dedupe deletes fiscalConfirmed row; ticker-feed
fiscal labels unconfirmed (#70 leftover); extractJson brace-in-string bug; hostile-QR
PBKDF2 on main thread; Finnhub key in logcat; CIK backfill lost-update; bug-reporter
sanitization gaps; + lows. Full details in each agent report (session transcript).

## Status log

- 2026-07-27: audit complete (35 findings), batch approved, this file created.
- 2026-07-27: all 11 smoothness-batch fixes applied; `:app:compileDebugKotlin` green.
  13 GitHub issues (#72–#84) filed for the rest. NOTHING COMMITTED YET — working tree
  holds both the TTS/prompt work and the bug-fix batch.

## Next steps (for a future session)

1. Everything through round 2 + model migration is APPLIED and assembleDebug is green.
   NOTHING COMMITTED. Working tree = 4 logical changesets:
   (a) TTS GA models + NotebookLM prompt feature, (b) round-1 smoothness batch
   ("closes #65-71"-era fixes + closes #72–#84 candidates from round-1... actually
   #72–#84 were the round-1 filed issues — close them in the commit),
   (c) latest-models migration (Opus 5 / Sonnet 5 / Gemini 3.x + pricing),
   (d) round-2 audit fixes (36 findings, no issues filed).
2. User to test on device: podcast end-to-end (script → TTS → playback → resume),
   re-adding an existing ticker no longer wipes data, QR share/scan round trip,
   morning brief, alerts after quiet hours.
3. Commit (4 commits per above, "closes #72" ... "closes #84" on the round-1 one),
   then `/release`.
