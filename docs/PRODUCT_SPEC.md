# VBoard Product Specification — v1.0

**Status:** Approved for implementation
**Owner:** Product (nvkudva@gmail.com)
**Last updated:** 2026-08-28
**Audience:** Engineering, QA, Design

All requirements carry IDs (`VB-###`) for traceability. "MUST" requirements are release blockers for v1; "SHOULD" requirements ship in v1 unless explicitly deferred with PM sign-off.

---

## 1. Vision, Positioning, Target User

### 1.1 Vision

VBoard is a voice-first Android keyboard: you talk, and clean, intent-shaped text appears — instantly, accurately, and without a single byte of audio leaving your phone. Typing is the fallback; speaking is the default.

### 1.2 Positioning

**Gboard-fast + Superwhisper-smart + private.**

- **Gboard-fast:** live words on screen as you speak (streaming partials < 300 ms), keyboard opens instantly, taps feel native.
- **Superwhisper-smart:** dictation output is cleaned — fillers removed, self-corrections resolved, punctuation and capitalization applied — with an optional on-device LLM pass that polishes toward intent.
- **Private:** 100% on-device speech processing. No audio upload, no transcript upload, no cloud ASR fallback, ever. This is the headline marketing claim and a hard engineering constraint.

### 1.3 Target user

English-speaking users on modern Android phones (2021+, Pixel 6+ / Snapdragon 7–8 class, Android 10+) who:
- send high volumes of messages (WhatsApp, Slack, SMS, email) and find thumb-typing slow;
- have tried built-in dictation and abandoned it because raw transcripts are messy ("um so like");
- care about privacy and distrust cloud keyboards; or
- have accessibility/RSI needs that make voice input preferable.

### 1.4 Product principles (tie-breakers for design decisions)

1. Voice is primary; the QWERTY exists so nobody is ever stuck.
2. Speed beats features. If a feature threatens latency targets (§7), cut the feature.
3. Never surprise the user with text they didn't say — cleanup must be predictable, reversible (raw mode), and off-by-default for the LLM tier.
4. Privacy claims are absolute. No "anonymized telemetry" of content, ever.

---

## 2. Personas & Top User Journeys

### 2.1 Personas

- **P1 "Priya", the message machine.** 29, product manager, Pixel 8. Sends 150+ messages/day. Wants dictation faster than typing and clean enough to send without editing. Primary persona; optimize everything for her.
- **P2 "Marcus", the privacy pragmatist.** 41, lawyer, Samsung S23. Will not use any keyboard that sends data off-device. Reads privacy policies. Needs verifiable on-device claims and a visible airplane-mode-still-works story.
- **P3 "Dana", hands-busy / accessibility.** 55, mild RSI. Dictates nearly everything, uses spoken commands ("new line", "period"), needs the fallback keyboard rarely but needs it to be forgiving (large-enough targets, haptics).

### 2.2 Journey A — First run & onboarding (critical path)

1. User installs VBoard, opens the app. Welcome screen states the privacy promise in one sentence.
2. **Enable step:** app deep-links to Android *Settings → Languages & input → On-screen keyboard* (`ACTION_INPUT_METHOD_SETTINGS`); user toggles VBoard on; app detects return and auto-advances.
3. **Select step:** app invokes the system input-method picker (`InputMethodManager.showInputMethodPicker()`); user selects VBoard; app detects selection and auto-advances.
4. **Mic permission:** app requests `RECORD_AUDIO` with a plain-language rationale ("Audio is processed only on this phone and never uploaded"). Denial is non-blocking — user proceeds with typing-only mode.
5. **Model download:** app offers the speech pack download (Zipformer + Parakeet; LLM pack separately, opt-in) over Wi-Fi with size shown up front, progress UI, pause/resume. User can skip and download later; keyboard works for typing immediately.
6. **Try it:** in-app practice field; user dictates a sentence, sees cleanup happen, done.

Target: a user who accepts all defaults completes steps 1–4 in under 90 seconds of interaction time (download excluded).

### 2.3 Journey B — Daily dictation in a messaging app

Priya opens WhatsApp → VBoard appears with QWERTY + prominent mic key → taps mic → compact voice bar replaces/overlays the bottom area → speaks 2 sentences with an "um" and a "no wait" → words stream live → she pauses → text finalizes and cleans itself (filler gone, correction applied, punctuation added) → she taps the field's Send action. Zero keyboard edits needed in the common case.

### 2.4 Journey C — Dictating into a search field

User taps a search box (browser, Play Store, in-app search) → taps mic → says "wireless earbuds under 100 dollars" → cleanup applies capitalization conservatively, no trailing period (field-aware, §9) → user hits the Search action key.

### 2.5 Journey D — Fallback typing

Voice models not yet downloaded, or a noisy environment, or a password field: user types on the QWERTY with autocorrect and suggestions exactly as they'd expect from a mainstream keyboard. No dead ends: every voice failure state offers "just type" implicitly because the keyboard is always there.

### 2.6 Journey E — Switching modes

Mic key on QWERTY enters voice; keyboard icon on the voice bar (or any external tap into the text field) returns to QWERTY; system IME switcher (globe/back gestures) is fully respected for switching to other keyboards.

---

## 3. Functional Requirements — Voice Bar & Dictation Lifecycle

### 3.1 States

The dictation subsystem is a state machine: `IDLE → LISTENING → FINALIZING → IDLE`, with terminal error states. QA tests every transition.

- **VB-101 (MUST):** Tapping the mic key from the QWERTY opens a compact voice bar anchored at the bottom (Gboard-style: shorter than the full keyboard, showing a live waveform/level indicator, a mic-stop control, and a keyboard-return control). The keyboard height reported to the app must not jump in a way that scrolls the target field out of view.
- **VB-102 (MUST):** LISTENING starts within 150 ms of mic-key tap when models are loaded (audio capture begins; UI shows active state). If models must be paged into memory, show a brief "warming up" spinner, max 2 s on reference hardware (§7).
- **VB-103 (MUST):** While LISTENING, streaming partials from the Zipformer transducer are committed to the text field in real time as `setComposingText` (composing region), so the app sees text but it remains visually provisional (underline/highlight per platform convention).
- **VB-104 (MUST):** Endpoint detection (sherpa-onnx endpointing; default trailing-silence ≈ 800 ms, configurable internally) transitions to FINALIZING: the utterance's captured audio is re-transcribed by Parakeet TDT 0.6B (int8, non-streaming), Tier-1 cleanup (§4) is applied, and the result **replaces** the composing partial text via a single atomic `commitText` (after `setComposingText("")`), then state returns to LISTENING for continuous dictation (the bar stays open) — the mic does not stop on endpoint.
- **VB-105 (MUST):** The replacement in VB-104 must be flicker-minimal: one composing-region swap, no character-by-character redraw, no cursor jump. The cursor lands at the end of the committed text.
- **VB-106 (MUST):** While an utterance is FINALIZING, the user may keep speaking; audio for the next utterance is buffered and streamed without loss (pipeline overlap). No audio gaps > 50 ms between utterances.
- **VB-107 (MUST):** Explicit stop: tapping the mic-stop control (or the keyboard-return control) ends capture, finalizes any in-flight utterance, then returns to QWERTY (keyboard-return) or idle bar (stop). Hiding the IME or the app losing input focus also stops capture immediately and finalizes the utterance from the buffered audio; audio capture NEVER continues while the voice bar is not visible.
- **VB-108 (MUST):** Auto-timeout: after 30 s of continuous silence in LISTENING, capture stops and the bar shows "Tap mic to resume". No indefinite hot mic.
- **VB-109 (MUST):** Auto-spacing: finalized utterances are joined to preceding text with correct spacing (single space unless preceding char is whitespace/newline/open-bracket; no leading space at field start or after newline).
- **VB-110 (SHOULD):** A subtle per-utterance "cleanup applied" affordance (e.g., brief shimmer on changed spans) so users learn what Tier 1 did. Must not block or delay commit.

### 3.2 Error states

- **VB-120 (MUST):** *No mic permission:* mic key tap shows an inline card in the keyboard area: "Microphone access needed — audio never leaves your phone" with a button deep-linking to the permission dialog (or app settings if permanently denied). Never a silent failure, never a crash.
- **VB-121 (MUST):** *Models missing/not downloaded:* mic key tap shows an inline card: "Download the speech pack (X MB) to dictate" with a Download button that opens model management (§6). QWERTY remains fully functional.
- **VB-122 (MUST):** *Model load failure / corrupt files:* detected by checksum at load; inline card offers "Repair download" (delete + re-download). Log a non-content diagnostic event.
- **VB-123 (MUST):** *Mic busy (another app holds audio input) or audio-focus loss (e.g., phone call):* show "Microphone unavailable" state; auto-retry when focus returns is NOT performed (user re-taps mic). Incoming call during dictation finalizes from buffered audio and stops capture.
- **VB-124 (MUST):** *ASR pipeline crash/hang:* watchdog — if Parakeet finalization exceeds 5 s, keep the streamed Zipformer partial as the committed final text (never lose user speech), surface nothing scarier than a toastless silent fallback, and log a diagnostic. Two consecutive watchdog trips disable Parakeet re-scoring for the session (Zipformer-only mode) and note it in Settings → Diagnostics.
- **VB-125 (MUST):** All error cards are dismissible and return the user to the QWERTY in one tap.

---

## 4. Functional Requirements — Cleanup Tiers & User Controls

### 4.1 Tier 1 — Deterministic rules engine (instant, always available)

Runs synchronously on the Parakeet final text during FINALIZING. Budget: ≤ 30 ms per utterance. Pure functions, unit-testable with a golden corpus.

- **VB-201 (MUST):** Filler removal: standalone "um", "uh", "er", "hmm" always removed; "like" and "you know" removed only in high-confidence discourse-filler positions (rule list maintained in code with the golden corpus); never remove when they carry meaning ("I like you", "you know the answer").
- **VB-202 (MUST):** Self-correction handling: trigger phrases "no wait", "I mean", "scratch that", "actually no", "correction" cause the engine to drop the corrected span and keep the replacement, using a bounded window (drop back to the previous phrase/clause boundary within the current utterance only — never rewrite already-committed prior utterances).
- **VB-203 (MUST):** Repeated-word dedup: immediate word repetitions ("the the", "I I") collapse to one, except legitimate doubles on an allowlist ("had had", "that that" heuristics) — when uncertain, keep both.
- **VB-204 (MUST):** Auto-punctuation & capitalization: sentence-initial caps, terminal punctuation per utterance intonation/heuristics (default period; question mark when the utterance starts with an interrogative pattern), "i" → "I", proper-noun capitalization limited to what the ASR emits (no dictionary-forced renaming).
- **VB-205 (MUST):** Spoken commands, processed before filler removal, exact-match on command phrases: "period/full stop", "comma", "question mark", "exclamation point/mark", "new line", "new paragraph", "colon", "semicolon", "open/close quote", "hyphen", "dash", "space", "delete that" (removes the last finalized utterance), "undo that" (alias). Command words used mid-sentence as content ("the Cretaceous period") are protected by a pause/position heuristic; when uncertain, treat as content.
- **VB-206 (MUST):** Tier 1 is idempotent: running it twice on its own output changes nothing (guards against double-cleanup on the Zipformer-fallback path of VB-124).

### 4.2 Tier 2 — On-device LLM refinement (optional)

Gemma 3 1B-class model via MediaPipe LLM Inference, applied to the finalized utterance after Tier 1.

- **VB-210 (MUST):** OFF by default. Enabled via Settings and via a quick toggle on the voice bar overflow. Requires separate model pack download (§6).
- **VB-211 (MUST):** Refinement scope: grammar repair, sentence-boundary fixes, intent-preserving tightening. Hard constraints in the prompt/decoding: never add facts, never change names/numbers/amounts, never translate, output length within ±30% of input. Utterances > 400 characters are refined in clause-safe chunks.
- **VB-212 (MUST):** Latency handling: Tier-1 text commits immediately (VB-104 unchanged); LLM refinement, when it completes (< 3 s budget, §7), replaces the utterance text only if the user has not since edited or moved the cursor into that span and the field still has focus. If the user has typed/edited, the refinement is discarded silently.
- **VB-213 (MUST):** Kill switch semantics: toggling Tier 2 off takes effect on the next utterance; no restart required.
- **VB-214 (SHOULD, v1.x):** Per-app/per-field refinement profiles (e.g., "messaging casual" vs "email formal"). Ship the plumbing (field metadata reaches the cleanup layer) in v1; ship profiles later. Do not build UI for this in v1.

### 4.3 Raw mode — escape hatch

- **VB-220 (MUST):** A "Raw dictation" toggle exists in two places: Settings → Dictation, and the voice-bar overflow menu (long-press mic also toggles for the next utterance only). Raw mode bypasses Tier 1 (except spoken commands, which remain active — VB-205) and Tier 2 entirely; the Parakeet transcript is committed verbatim.
- **VB-221 (MUST):** Raw mode state is clearly indicated on the voice bar (label/badge). Session-scoped raw (long-press) reverts automatically when the voice bar closes.

---

## 5. Functional Requirements — QWERTY Fallback Keyboard

- **VB-301 (MUST):** English QWERTY layout with: tap typing; shift with auto-shift at sentence start, double-tap caps-lock (with lock indicator); long-press on keys for accents/diacritics, digits (top row long-press), and symbols per a defined key-popup map; dedicated symbols layer (?123) and secondary symbols layer (=\<); emoji panel with category tabs, search deferred to v1.x, and recently-used row; comma/period keys on the main layer.
- **VB-302 (MUST):** Backspace: single tap deletes one grapheme cluster (never splits emoji/ZWJ sequences); long-press auto-repeats, accelerating after 1 s to word-wise deletion.
- **VB-303 (MUST):** Spacebar cursor control: horizontal slide on spacebar moves the cursor character-wise (Gboard behavior). No text selection via spacebar in v1.
- **VB-304 (MUST):** Haptics: key-press haptic (system `KEYBOARD_TAP` / vibration effect), user-toggleable, respects system haptics-off. Optional key-press sound, OFF by default.
- **VB-305 (MUST):** Autocorrect: bundled English dictionary (target ≤ 4 MB compressed); corrects the composing word on space/punctuation using edit-distance + keyboard-adjacency scoring; the corrected word is visibly indicated in the suggestion strip; **backspace immediately after an autocorrection reverts it to the typed literal** (one-shot revert).
- **VB-306 (MUST):** Suggestion strip: 3 slots — current-word completions and next-word predictions from the bundled n-gram data; middle slot is the default/autocorrect candidate; the typed literal is always reachable (left slot). Tapping a suggestion commits it plus a space (space suppressed before punctuation).
- **VB-307 (MUST):** User dictionary learning: words the user types and keeps (not autocorrected away, ≥ 2 uses) are learned locally for suggestions; learned data is stored on-device only, excluded from any backup transport (`android:allowBackup` rules exclude it), and clearable in Settings (VB-505). No learning in fields flagged in §9.
- **VB-308 (MUST):** Mic key sits left of spacebar (primary position, visually accented) — voice is the flagship action. Globe/switch-IME key present per system convention when multiple IMEs are enabled.
- **VB-309 (MUST):** Light and dark themes, following the system theme automatically; manual override in Settings. Two themes only in v1.
- **VB-310 (MUST):** Layout correctness across: portrait/landscape, gesture-nav and 3-button nav insets, display cutouts, split-screen, and font/display scale up to 1.3×. Landscape uses the same layout scaled (no thumb-split layout in v1).
- **VB-311 (SHOULD):** Number row toggle (off by default) in Settings.
- **VB-312 (MUST):** No swipe/glide typing anywhere in v1 (see §10) — do not leave dead code paths or settings stubs for it.

---

## 6. Functional Requirements — Onboarding & Model Management

### 6.1 Model packs

| Pack | Contents | Approx. download size | Required for |
|---|---|---|---|
| Speech pack | Streaming Zipformer transducer (small) + Parakeet TDT 0.6B int8 ONNX + endpointing config | ~700 MB (exact size shown in UI from manifest) | Dictation |
| Refine pack | Gemma 3 1B-class (MediaPipe task bundle) | ~550 MB | Tier 2 only |

- **VB-401 (MUST):** No models in the APK. APK installs and QWERTY works with zero downloads (see §7 APK size).
- **VB-402 (MUST):** Downloads default to **Wi-Fi/unmetered only**; user can explicitly override to cellular per download with a size warning. Downloads run via WorkManager/DownloadManager: survive app death, resume after connectivity loss (HTTP range resume), show progress (percent + MB) in onboarding, in Settings, and as a system notification.
- **VB-403 (MUST):** Integrity: every model file verified against a SHA-256 from a signed manifest before activation; failed verification auto-deletes and offers retry. Atomic activation (download to temp dir, verify, rename) — the ASR engine never sees partial files.
- **VB-404 (MUST):** Pre-flight storage check: require pack size + 15% headroom free; otherwise show exact space needed and a shortcut to system storage settings.
- **VB-405 (MUST):** Retry UX: transient failures auto-retry with backoff (3 attempts) before surfacing an error card with a manual Retry. Error messages distinguish: no network / not on Wi-Fi / out of storage / verification failed / server error.
- **VB-406 (MUST):** Settings → Speech & models shows: per-pack status (not downloaded / downloading % / ready / update available), size on disk, Delete button (with confirmation; deleting the speech pack returns mic-tap to VB-121 state), and Re-download.
- **VB-407 (MUST):** Model updates: app checks the manifest at most daily on Wi-Fi; updates are user-confirmed (no silent multi-hundred-MB downloads), and the old pack keeps working until the new one is verified.
- **VB-408 (MUST):** Onboarding is skippable after the Enable/Select steps; every skipped step (mic permission, downloads) is re-offerable contextually (VB-120/121) and from Settings. The app never nags more than once per session.
- **VB-409 (MUST):** Onboarding detects and reflects real state on every launch (IME enabled? selected? mic granted? packs ready?) — it is a checklist, not a one-shot wizard, and deep-links to whichever step is incomplete.

---

## 7. Non-Functional Requirements

Reference hardware: **Pixel 7** (and equivalents: Snapdragon 7-8 class, ≥ 6 GB RAM). Measured at the 90th percentile unless stated; CI perf tests gate release.

### 7.1 Latency

- **VB-601 (MUST):** First partial word visible < **300 ms** from speech onset (models warm).
- **VB-602 (MUST):** Parakeet final replacement < **1.2 s** after endpoint for a 10 s utterance; < 2.5 s for a 30 s utterance (max utterance length 30 s; longer speech is force-endpointed).
- **VB-603 (MUST):** Tier-1 cleanup ≤ **30 ms** (included in VB-602 budget).
- **VB-604 (MUST):** Tier-2 LLM refinement < **3 s** after final commit for a ≤ 200-char utterance.
- **VB-605 (MUST):** Keyboard cold open (field focus → keyboard fully drawn, process cold) < **150 ms**; warm open < 80 ms.
- **VB-606 (MUST):** Key tap→glyph-committed latency < **40 ms**; haptic within 20 ms of touch-down.
- **VB-607 (MUST):** Mic tap → LISTENING (warm) < 150 ms (VB-102).

### 7.2 Memory

- **VB-610 (MUST):** IME process PSS ceilings: ≤ **60 MB** typing-only (no ASR loaded); ≤ **400 MB** during active dictation (Zipformer + Parakeet resident); Tier-2 inference runs in a separate process/service with its own ≤ 1.5 GB transient budget, loaded on demand and released after 60 s idle.
- **VB-611 (MUST):** ASR models unload after 5 min without dictation; reload must meet the 2 s warm-up bound (VB-102). The IME process must survive (not OOM-kill) on 6 GB devices with a heavy foreground app.

### 7.3 Battery, size, stability

- **VB-620 (MUST):** Battery: active dictation ≤ 12% battery/hour of continuous speech on reference hardware; idle keyboard (visible, no input) adds no measurable drain (no wakelocks, no polling); mic fully released within 500 ms of leaving LISTENING.
- **VB-621 (MUST):** APK download size < **30 MB** (models excluded; includes QWERTY dictionary).
- **VB-622 (MUST):** Crash-free sessions ≥ **99.8%**; ANR rate below Play vitals bad-behavior threshold. A crash in the ASR/LLM layer must never take down typing (isolate via separate process or guarded boundary — typing survives).
- **VB-623 (MUST):** Zero network calls at dictation time. Verifiable: dictation works identically in airplane mode (post-download). This is a release-gate test.

---

## 8. Release Milestones (engineering sequencing)

Sequencing only — dates owned by engineering leads. Each milestone is demoable end-to-end.

- **M1 — Typing keyboard.** IME service, QWERTY per §5, settings skeleton, themes. Exit: VB-301–312 pass; VB-605/606 met.
- **M2 — Streaming dictation.** Voice bar, Zipformer partials, endpointing, lifecycle §3.1 with Zipformer text as final. Exit: VB-101–110 (with streamed text standing in for VB-104's re-scored final), VB-601.
- **M3 — Two-pass + Tier 1.** Parakeet re-scoring replacement, Tier-1 engine + golden corpus, error states §3.2. Exit: VB-104, VB-120–125, VB-201–206, VB-602/603.
- **M4 — Model management + onboarding.** Download pipeline, checklist onboarding, field-type awareness. Exit: §6, §9, VB-621, VB-623 airplane-mode gate.
- **M5 — Tier 2 + hardening.** LLM refine pack, raw mode polish, perf/memory/battery gates, beta. Exit: VB-210–221, §7 complete, crash-free ≥ 99.8% over beta population.

---

## 9. Field-Type Awareness

Driven by `EditorInfo.inputType` and `imeOptions`.

- **VB-701 (MUST):** IME action key reflects the field's action (Search, Send, Go, Done, Next, Enter) with correct icon/label and behavior; voice bar exposes the same action so a user can dictate-then-send without returning to QWERTY.
- **VB-702 (MUST):** Password fields (all password/PIN input-type variations): voice input **disabled** (mic key hidden/inert with tooltip "Voice off for passwords"), suggestions/autocorrect **disabled**, learning **disabled**, keypress sound/haptic pattern unchanged (no side channel), incognito-style handling; no text from these fields is ever stored or logged.
- **VB-703 (MUST):** Email address and URI fields: autocorrect off, capitalization off, suggestions limited to user-typed continuations; long-press "@" and "." conveniences; dictation remains available but Tier 1 disables terminal punctuation and sentence casing.
- **VB-704 (MUST):** Numeric/phone fields: numeric layer shown; voice disabled in v1 (digit dictation deferred).
- **VB-705 (MUST):** `textNoSuggestions` and incognito flags (`IME_FLAG_NO_PERSONALIZED_LEARNING`) honored: no suggestions and/or no learning respectively.
- **VB-706 (MUST):** Search fields: cleanup applies "query style" — no trailing period, minimal capitalization (VB-204 relaxed).
- **VB-707 (MUST):** Multiline vs single-line: "new line" command inserts newline only where the field permits; otherwise it is ignored (not typed as text).

---

## 10. Explicit v1 Non-Goals

Cut from v1; do not build, stub, or design around:

- **VB-801:** Swipe/glide typing.
- **VB-802:** Any language other than English (UI or ASR).
- **VB-803:** Cloud sync of any kind (settings, dictionary, history). No accounts, no sign-in.
- **VB-804:** Custom themes marketplace / user theming beyond light-dark.
- **VB-805:** iOS, tablets-optimized layouts, foldable-specific layouts, Wear/Auto.
- **VB-806:** Voice commands for app control ("send it", "open WhatsApp") — dictation and text-editing commands only.
- **VB-807:** Clipboard manager, stickers/GIFs, translation.
- **VB-808:** Digit/number-field dictation (VB-704) and emoji-by-voice.

---

## 11. Success Metrics

Instrumentation constraint: metrics are computed **on-device** from non-content events (counts, durations, states — never text or audio) and reported only if the user opts in to anonymous usage statistics (default OFF; see VB-902). Targets at 90 days post-launch:

| Metric | Target |
|---|---|
| Activation: install → VBoard enabled + selected | ≥ 60% |
| Speech pack download completion (of users who start it) | ≥ 85% |
| D7 retention as default IME | ≥ 40% |
| Voice share: % of committed characters entered via dictation among mic-granted users | ≥ 50% |
| Dictation "keep rate": finalized utterances not edited/deleted within 10 s | ≥ 80% |
| p90 first-partial latency (field telemetry) | < 300 ms |
| Crash-free sessions | ≥ 99.8% |
| Play Store rating | ≥ 4.3 |

Guardrails: uninstall within 24 h ≤ 15%; autocorrect revert rate (VB-305) ≤ 8% of corrections.

---

## 12. Privacy Commitments (product-level requirements)

- **VB-901 (MUST):** All audio capture, ASR, cleanup, and LLM inference occur on-device. No audio, transcripts, or field text are transmitted, logged remotely, or written to shared storage. The only network traffic is model downloads (VB-402) and, if opted-in, non-content usage stats and crash reports (crash reports scrubbed of any text buffers).
- **VB-902 (MUST):** Anonymous usage statistics are opt-IN (unchecked by default) with a plain-language description of exactly what is sent. Crash reporting may be opt-out but must be content-free by construction.
- **VB-903 (MUST):** Play policy compliance for IMEs: prominent disclosure of mic use, accurate Data Safety form ("no data collected" for content categories), and the system's standard "this keyboard may collect text you type" warning is anticipated in onboarding copy ("Android shows this for every keyboard — here's why VBoard is different"), see risk R4.
- **VB-904 (MUST):** Settings → Privacy page: states the on-device guarantee, links the privacy policy, and hosts: clear learned dictionary (VB-307), clear all local data, and the telemetry toggle.

## 12.1 Settings Surface (complete v1 list)

- **VB-501 (MUST):** Settings app (launcher activity + accessible from keyboard overflow) with exactly these sections: **Dictation** (Tier-2 refine toggle + pack status, Raw mode default, spoken-commands reference card), **Typing** (autocorrect on/off, suggestions on/off, number row, haptics, sound), **Appearance** (theme: system/light/dark), **Speech & models** (§6, VB-406), **Privacy** (VB-904), **Diagnostics** (on-device perf/error log viewer, exportable by explicit user share action only), **About/Onboarding checklist** (VB-409).
- **VB-502 (MUST):** Every toggle takes effect immediately (next keystroke/utterance); no restarts.
- **VB-503 (MUST):** All settings stored locally (DataStore); no cloud backup of learned dictionary (VB-307); other prefs may back up via Auto Backup except privacy-sensitive items.
- **VB-505 (MUST):** "Clear learned words" and "Clear all data" actions with confirmation.

---

## 13. Risks & Mitigations

- **R1 — Model download abandonment (~700 MB is heavy).** *Mitigation:* keyboard is fully usable for typing pre-download (VB-401); download is resumable and background (VB-402); size stated up front; contextual re-prompt only at mic tap (VB-121); metric tracked (§11, ≥ 85% completion). *Contingency:* if completion < 70% in beta, ship a smaller Parakeet variant or defer Parakeet as an optional "accuracy pack" with Zipformer-only default.
- **R2 — RAM pressure / OOM kills on 6 GB devices.** *Mitigation:* strict PSS budgets and idle unloading (VB-610/611); Tier 2 in a separate killable process; Zipformer-only degraded mode (VB-124) doubles as a low-memory mode; device-class gate — on devices below spec, default Tier 2 off and warn.
- **R3 — IME switching friction (users never enable/select).** *Mitigation:* two-step deep-linked onboarding with state detection (VB-409); activation metric ≥ 60% (§11); store-listing and onboarding videos showing the two toggles. *Contingency:* in-app rehearsal animation of the Settings toggle if drop-off concentrates at the Enable step.
- **R4 — Play policy / user trust: Android's "keyboard may collect your data" interstitial scares users; IME + mic + network permission looks bad.** *Mitigation:* prominent disclosure and accurate Data Safety form (VB-903); airplane-mode demo in onboarding ("turn on airplane mode — dictation still works"); telemetry opt-in default-off (VB-902); publish a third-party-auditable network behavior statement (no network at input time, VB-623).
- **R5 — Cleanup overreach destroys user trust (removed a word they meant).** *Mitigation:* conservative-by-default rules ("when uncertain, keep it" — VB-201/203/205); golden-corpus regression suite; raw mode one long-press away (VB-220); keep-rate metric ≥ 80% (§11) with rule-level on-device counters to find offending rules.
- **R6 — Latency targets missed on thermal throttling / mid-tier devices.** *Mitigation:* perf CI on reference hardware; watchdog fallback to streaming-only (VB-124); int8 quantization locked in; utterance length cap 30 s (VB-602).
- **R7 — sherpa-onnx / MediaPipe dependency risk (ABI size, upstream bugs).** *Mitigation:* pin versions; ship arm64-v8a only (covers target device class, helps VB-621); abstraction boundary around ASR engine so models/runtimes can be swapped without touching IME code.

---

## 14. Acceptance & Traceability

- Every VB-### above is a testable requirement; QA maintains a test-case matrix keyed by these IDs. Release requires: all MUST requirements passing, perf gates of §7 green on reference hardware, and the airplane-mode privacy gate (VB-623).
- Changes to locked decisions (§1, architecture, scope cuts) require PM sign-off and a spec version bump.
