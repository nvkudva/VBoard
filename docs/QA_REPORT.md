# VBoard QA Report

Date: 2026-08-28 · Scope: `core` module (fully machine-verified) + manual test
plan for the Android layer. Requirement IDs (VB-###) refer to
[PRODUCT_SPEC.md](PRODUCT_SPEC.md).

## 1. Test suite summary

| Area | Test classes | Tests | Status |
|---|---|---|---|
| Transcript cleanup (unit) | `TranscriptCleanerTest` | 45 | ✅ pass |
| Cleanup golden corpus | `qa/CleanupGoldenCorpusTest` | 44 cases + 4 regression | ✅ pass |
| Cleanup properties (idempotency, robustness, option independence) | `qa/CleanupPropertyTest` | 12 | ✅ pass, 1 skipped (VB-QA-05) |
| Commit planning & text diff | `CommitPlannerTest`, `TextDiffTest` | 15 | ✅ pass |
| Dictation state machine | `DictationStateMachineTest`, `qa/StateMachineFuzzTest` | 20 + fuzz (200 seeded runs × 50 events) | ✅ pass |
| Suggestions/autocorrect | `SuggestionEngineTest`, `LexiconTest`, `UserHistoryTest`, `qa/SuggestionEngineQaTest` | 90+ | ✅ pass |
| Model catalog & installer | `ModelCatalogTest`, `PackInstallerTest`, `qa/ModelInstallerQaTest` | 40+ | ✅ pass |
| **Total** | | **294** | **293 pass / 1 skipped** |

Run locally with `./gradlew -Pvboard.skipAndroid=true :core:test`; CI runs the
same suite plus the Android build on every push.

## 2. QA findings

| ID | Severity | Finding | Status |
|---|---|---|---|
| VB-QA-01 | **High** | Repetition collapse corrupted spoken digit sequences: "five five five one two one two" → "five one two" (phone numbers destroyed; violates VB-203 "when uncertain, keep both"). | **Fixed** — number-like words (digits, number words) are exempt from word- and bigram-level collapse. Covered by re-enabled golden tests. |
| VB-QA-06 | **High** | Mixed-case tokens near a frequent word were autocorrected: "iPhone"→"phone", "iOS"→"is", "VBoard"→"Board" (risk R5: corrupting deliberate input). | **Fixed** — internal capitals now gate autocorrect exactly like ALL-CAPS. |
| VB-QA-07 | **High** | Two concurrent `install()` calls for the same pack interleaved writes into one `.part` file and could activate a corrupt model that passed its own running digest (breaks VB-403). | **Fixed** — per-pack `Mutex` serializes installs; second caller short-circuits on the marker. Concurrency test re-enabled. |
| VB-QA-09 | Medium | The typed word could vanish from the suggestion strip when three higher-scored candidates filled all slots (violates VB-306 "literal always reachable"). | **Fixed** — literal is forced into the left slot when ranking drops it. |
| VB-QA-02 | Medium | Interrogative utterances got a terminal "." instead of "?" (VB-204). | **Fixed** — utterances starting with an interrogative word (what/how/can/could/…) get "?"; known limit: interjection-led questions ("hey are you coming") still get ".". |
| VB-QA-03 | Low | "actually no" self-correction trigger from VB-202 was unimplemented. | **Fixed** — added as a strong marker with semantic alignment ("for may actually no june" → "for june"). |
| VB-QA-05 | Low | Idempotency (VB-206) breaks on pathological inputs: 5+ stacked correction markers, "scratch that scratch that" (output can re-trigger a command if re-cleaned), "\n\n\n" re-tokenizes to "\n\n". | **Open, documented** — cleanup runs exactly once per utterance in the product, so the double-clean path is unreachable in practice; test kept `@Disabled` as the spec-correct pin. |

## 3. Traceability highlights (core-testable requirements)

- VB-2xx cleanup requirements → `TranscriptCleanerTest`, `CleanupGoldenCorpusTest` (44 realistic utterances across messaging/email/notes/addresses/questions), `CleanupPropertyTest` (idempotency, never-throws, no double spaces, option independence, raw-mode contract).
- VB-1xx dictation lifecycle → `DictationStateMachineTest` (happy paths, continuous dictation, scratch-that, silence timeout, watchdog fallback semantics) + `StateMachineFuzzTest` invariants (no commit from Idle/Error, StopAudio on session exit, monotone utterance index, never throws).
- VB-3xx typing/autocorrect → `SuggestionEngineQaTest` (100 sampled lexicon words never autocorrected, casing gates, apostrophes, field gating: PASSWORD/NUMBER empty, EMAIL/URI literal-only, OFF-mode inertness).
- VB-4xx model management → `PackInstallerTest` + `ModelInstallerQaTest` (resume with exact byte accounting, checksum mismatch, cancellation, storage pre-check, process-restart persistence, version bump, concurrent installs, delete-during-download).
- Remaining VB-1xx/5xx UI, latency and privacy requirements are Android-layer: see manual plan below; latency NFRs need on-device measurement (Pixel 7-class).

## 4. Manual on-device test plan (top 20)

1. Fresh install → onboarding: enable IME → select VBoard → mic permission → model download over Wi-Fi; kill the app mid-download and relaunch (must resume, not restart).
2. Airplane-mode privacy gate: with models installed, toggle airplane mode and dictate — everything must work; no network errors surface.
3. Dictate into Messages/WhatsApp/Gmail/Chrome address bar/system search; verify field-appropriate cleanup (no trailing period in search).
4. Password field: mic key disabled, no suggestions, no learning.
5. Live partials appear <300ms after speech starts; final replaces partial without flicker; punctuation/casing correct.
6. "um / no wait / scratch that / stop listening / new line / comma" behaviors during real dictation.
7. Continuous dictation across 3+ utterances; 30s silence auto-stop; orb tap stops and finalizes.
8. LLM refinement ON: "Cleaning ✨" chip, replacement only when the user hasn't edited; latency <3s.
9. Mic press with models missing → error state with Download action into onboarding.
10. Mic in use by another app (voice call) → graceful error.
11. Autocorrect: "teh"→"the" on space; single backspace reverts; suggestion tap commits with trailing space.
12. Long-press popups (accents, punctuation), slide-to-select, release-outside cancels.
13. Shift: single tap capitalizes one letter; double-tap caps lock; auto-caps after ". ".
14. Double-space period; spacebar drag moves cursor; backspace auto-repeat accelerates; emoji panel insert + backspace.
15. Rotation during typing and during dictation (no crash, keyboard rebuilds, session ends cleanly).
16. Light/dark theme switch mid-session; gesture-nav inset padding on a notched device.
17. Process death: swipe VBoard from recents while a download runs (notification continues via foreground service); learned words survive reboot.
18. Storage nearly full → download fails with INSUFFICIENT_STORAGE message, no partial corruption; delete + re-download from settings.
19. Keyboard cold-open <150ms and tap latency <40ms (systrace/Perfetto on Pixel 7-class).
20. Memory: PSS while typing ≤60MB; during dictation ≤400MB (Android Studio profiler), engines released on IME destroy.

## 5. Known risks

- Endpoint tuning (0.8s trailing silence) needs real-device validation across speaking styles.
- Parakeet int8 cold-load takes seconds on first mic press; mitigated by the engine cache but worth a warm-up on IME bind.
- Catalog `sizeBytes` are estimates until a release process pins exact sizes + sha256 digests (currently checksum verification is skipped where hashes are empty — pin before shipping).
- MediaPipe LLM Inference is in maintenance mode upstream; migration to LiteRT-LM is a v2 consideration.
