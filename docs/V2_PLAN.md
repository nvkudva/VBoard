# VBoard V2 Plan

Status: **proposed, not approved. Nothing here is being built.**
Date: 2026-08-29 · Sources: product, design, engineering-management and performance
reviews, plus two rounds of recorded debate between product and design.

Companion documents:
- [V2_PROPOSALS.md](V2_PROPOSALS.md) — every idea proposed, the debate, and what was cut
- [PERFORMANCE_REVIEW.md](PERFORMANCE_REVIEW.md) — the performance findings
- [QA_REPORT.md](QA_REPORT.md) — defects and test coverage

---

## 1. The framing that shapes this plan

**Thirteen proposed items, zero external users.**

This is a V2 roadmap written before V1 has met a single person outside the repo. At the
time of writing: the strip/toolbar unification is not built, the AI-fix toolbar is
implemented but not mounted, the text core destroys 64% of Unicode
([QA_REPORT.md §3.1](QA_REPORT.md)), and there is no telemetry — which means the metric
product and design agreed to steer by **cannot be computed today**. Adopting a metric we
cannot measure is how roadmaps become fiction.

The plan therefore opens with a wave that contains no features.

---

## 2. The convergent insight, and the flaw in it

Product and design independently proposed the same idea, reasoning from opposite
directions — product from *"what can we do that Gboard structurally cannot"*, design from
*"what is the 20M streaming model even for, now that the accuracy model is mandatory"*.

> **Every utterance is transcribed twice and one transcript is thrown away.**
> `FinalTranscriptPolicy.choose(decoded, partial)` treats the streaming hypothesis as a
> failure fallback, never as a second opinion. Where two independently-trained recognizers
> agree, the word is right; where they disagree, that word is the most likely error in the
> sentence. Free per-word confidence, zero extra compute, structurally unavailable to a
> cloud keyboard running one recognizer.

**The flaw neither of them caught: the two recognizers do not share a tokenization.**
Their own test asserts
`FinalTranscriptPolicy.choose("Meet me at eight.", "meet me at 8")` — one model writes
"eight", the other "8", with different casing and different terminal punctuation. A naive
word-diff reports three disagreements where the models agree completely.

The confidence signal's precision is therefore entirely a function of a normalization
layer (number words ↔ digits, casing, contractions, punctuation) — **and that is the same
normalization the spoken-format work needs.** They are one foundation, not two features,
and the plan treats them as one wave.

---

## 3. Waves

### Wave 0 — Finish V1, make it measurable

No new features. Gates everything below.

| # | Item | Owns | Size |
|---|---|---|---|
| W0.1 | **QA Package A — Unicode-safe text core** ([QA_REPORT.md §9](QA_REPORT.md)) | `core/text/Tokens.kt`, `TranscriptCleaner.kt` (`ARTIFACT_REGEX` only) | M |
| W0.2 | Strip/toolbar row unification + AI-fix mount + attribution UI + working-set transcript | `app/keyboard/SuggestionStripView.kt`, `ToolbarView.kt`, `app/ime/VBoardImeService.kt`, `app/correct/AiFixController.kt` | M–L |
| W0.3 | **Draft rescue** | `app/voice/VoiceSessionController.kt`, `app/ime/VBoardImeService.kt` | M |
| W0.4 | Instrumentation: send-ready rate, time-to-send-ready, opt-in telemetry, content-free crash reporting | `core/metrics/**` (new), `app/settings/**` | M |
| W0.5 | Performance and size gates measured; assign a standing owner to `core/text/` | `app/build.gradle.kts`, `docs/PERFORMANCE_REVIEW.md`, `core/text/**` | M |

**W0.3 is the plan's most important reordering.** Draft rescue was ranked sixth on the
feature list. It is not a feature — it is a data-loss bug whose existence is already
recorded as a `TODO` in `finishSession()` saying that an input connection which dies
before the final pass returns loses the user's speech. It belongs in the bug queue, above
every V2 item.

**W0.4 is the precondition for the metric ruling in §4 being real rather than
aspirational.**

Concurrency: W0.1 ∥ W0.4 ∥ W0.5 can run together. W0.2 and W0.3 both want
`VBoardImeService.kt` and are strictly sequential — **W0.3 first**, because it is the bug.

### Wave 1 — Confidence foundation

| # | Item | Owns | Size |
|---|---|---|---|
| W1.1 | Transcript normalization + two-model alignment + per-word confidence | `core/confidence/**` (new), `core/session/FinalTranscriptPolicy.kt` | L |
| W1.2 | Spoken-format intelligence (times, money, dates, spoken email addresses) | `core/format/**` (new), `core/text/Tokens.kt` | M |

One owner, sequential — W1.2 consumes W1.1's normalizer. Pure `core`, no Android, no file
contention with Wave 0 except `core/text/`.

**Gate on W1.1: measured disagreement precision against a hand-labeled corpus of ≥200
utterances, published as a number either way.** Below roughly 70%, W1.2 proceeds and
everything in Wave 3 that consumes confidence is **cancelled, not deferred** — if the
signal is mostly normalization artifacts, every downstream consumer is poisoned at the
source.

**Ordering hazard for W1.2:** format conversion must run *before* `ContentGuard`, and its
output must then be shielded. `ContentGuard.needsShield` returns true on any digit, so
getting the order wrong means the tokenizer tears `4:30pm` into `4. 30pm` — the feature
would fight the safety mechanism that exists to protect it. Every conversion must also
emit a `MECHANICAL` edit so it is attributable and revertible; spoken numbers have already
destroyed user text once in this codebase (VB-QA-01, phone numbers collapsed by repetition
handling).

### Wave 2 — Interaction

| # | Item | Owns | Size |
|---|---|---|---|
| W2.1 | Hold-to-talk + release-to-send | `app/keyboard/KeyboardView.kt`, `app/voice/VoiceBarView.kt`, `app/ime/VBoardImeService.kt` | M |
| W2.2 | Force-endpoint on any touch outside the keyboard | `app/ime/VBoardImeService.kt`, `app/voice/VoiceSessionController.kt` | S |
| W2.3 | Adaptive endpointing, re-scoped after W2.1 | `app/voice/AsrEngines.kt` | S–M |

**W2.1 must ship alongside tap-to-toggle, never replacing it. This is not negotiable.**
A press-and-hold gesture is hostile to TalkBack and to the RSI/motor-impairment persona —
the persona who dictates *most*. Shipping hold-to-talk as a replacement would silently
undo the accessibility work already funded and delivered. Neither product nor design
flagged this.

W2.1 also retires the long-open silence-timeout question for the 80% case: release *is*
the endpoint, so the trailing-silence wait disappears from short utterances entirely.

### Wave 3 — Learning

Confusion learning · biased re-decode of disputed spans · clipboard-harvested name biasing.

All three consume W1.1. **None is dispatched until there is real correction data from beta
users** *and* W1.1's precision number is known. Clipboard-harvested biasing additionally
needs an explicit user-facing privacy story before design — clipboard content influencing
transcription is defensible, but only if stated out loud.

---

## 4. Adjudications

Five disagreements survived two rounds of debate between product and design. They were
deliberately not averaged.

**D1 — Do mechanical edits get undo pills?**
*Ruling: one undo ring holding every change; selective presentation.* Mechanical changes
collapse to a single non-interactive line ("Fixed spelling and spacing"); editorial
changes get individual pills; one affordance expands the rest. Rationale: attribution
exists to catch text the user did not say. A capital letter is not a surprise; a reworded
clause is. This ruling is also the cheapest to reverse — it changes a filter, not an
architecture.

**D2 — Does autocorrect join the undo ring?**
*Ruling: no.* Backspace-reverts-autocorrect is shipped, tested, and universal muscle
memory. Trading a working reflex for architectural consistency is a downgrade. The ring
may observe autocorrect events for display completeness, but backspace-immediately-after
keeps its one-shot semantics and consumes no ring slot.

**D3 — Does tap-to-select-then-speak inherit the bug it was designed to fix?**
*Ruling: yes — cut the feature.* Design argued referential editing ("change X to Y") fails
because the recognizer mis-hears the correction command. Product inverted the diagnosis
and sharpened it: the **target** is a word the recognizer itself produced, so it
reproduces reliably; it is the **replacement** — the novel word — that cannot be heard. So
tap-to-select removes the easy half and leaves the hard half. Cut both referential editing
and the spelling escape hatch from V2. If beta data shows users attempt voice editing,
build **spell-it-to-teach-it first and standalone** — it is independently valuable for
proper nouns — and only then reconsider.

**D4 — Voice bar chrome.**
*Ruling: collapses into work already scheduled.* The bar being exactly keyboard height is
non-negotiable — any delta reflows the host app twice per dictation. Design conceded the
tall transcript. Once committed text lives in the field, the only thing left to display is
the in-flight partial plus controls, and that belongs in the unified row, not a separate
bar. Handed to W0.2 as an input.

**D5 — Is word error rate a roadmap input?**
*Ruling: disqualified as a prioritisation input; retained as a regression signal.* The
reductio: filler removal and repetition collapse make WER *worse by construction*, so if
WER ever won a prioritisation argument, the implied action would be deleting the
differentiator. Adopt **send-ready rate** (dictated characters reaching a Send action with
zero manual edits) as north star, plus time-to-send-ready; demote keep-rate to a relative
guardrail. WER becomes a CI regression gate **on raw pre-cleanup transcripts only**, never
an argument about cleaned output.

---

## 5. What is cut

- **Referential voice editing and the spelling escape hatch** (per D3) — three speculative
  layers deep with no user evidence.
- **Prosody-driven question marks.** The pushback was the strongest in the review:
  *"150 lines of DSP, no model" is the tell.* Pitch-rise detection on 16kHz mono works at a
  desk and fails in a car, a bar, or with any speaker whose prosody is not General
  American — and its failure mode is inserting a question mark into a statement, which is
  precisely the cleanup-overreach risk the conservative rule set exists to prevent. The
  known limit it was meant to fix (interjection-led questions getting a period) is better
  addressed with a twenty-line interjection allowlist: deterministic and testable.
- **Register-aware punctuation** — its own author ranked it lowest.

Four of thirteen cut. Two-thirds of the remainder sits behind a gate rather than a date.

---

## 6. Two decisions needed

**1. Does V2 start before or after beta?**
Recommendation: Wave 0 now, Wave 1 concurrent with beta, Waves 2–3 after first user data.
Wave 1 is pure `core` with no user-facing surface, so it is the one thing safe to build
without evidence. Everything past it is a guess until someone outside the team has used
the keyboard.

**2. Do we accept that the confidence idea's value is unproven until measured?**
Two independent reviewers converging is evidence about an idea's *appeal*, not its
*precision*. The W1.1 gate makes that testable for the first time. Committing Wave 3 to a
date now would mean committing to a date for features whose input signal has not been
measured.

---

## 7. Verified facts underpinning this plan

Both were checked against the source rather than taken on assertion.

**The 20M streaming model is a subsystem, not a download line item.** `enableEndpoint` and
all three endpoint rules live on the *streaming* recognizer config; the offline recognizer
config has no endpointing whatsoever. Removing the streaming model means adding a VAD,
rewriting utterance segmentation, replacing the liveness heartbeat that drives the silence
timer, and replacing the watchdog whose fallback branch *is* the streaming partial. Four
jobs, one of them architectural. It is 21% of the required download and the most
defensible part of it.

**The proposed VB-103 safety rule does not hold.** "Stream only into multiline fields with
no Send/Search action" still streams into WhatsApp, Slack and Messages, whose compose
fields are multiline with send as a separate in-app button — so the failure it was designed
to prevent (reading plausible-but-wrong text, then tapping send) is fully available in the
three apps that matter most. The correct mechanism is force-endpointing on any signal the
user touched outside the keyboard, which closes the window to decode latency rather than to
an 800ms timer.
