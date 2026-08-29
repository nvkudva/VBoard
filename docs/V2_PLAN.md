# VBoard V2 Plan

Status: **approved and partly built.** Wave 1.5 (§3) has landed in full; nothing
else in this plan has started. Wave 0.5 is the next item.
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
implemented but not mounted, and there is no telemetry — which means the metric
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
| W0.1 | Strip/toolbar row unification + AI-fix mount + attribution UI + working-set transcript | `app/keyboard/SuggestionStripView.kt`, `ToolbarView.kt`, `app/ime/VBoardImeService.kt`, `app/correct/AiFixController.kt` | M–L |
| W0.2 | **Draft rescue** | `app/voice/VoiceSessionController.kt`, `app/ime/VBoardImeService.kt` | M |
| W0.3 | Instrumentation: send-ready rate, time-to-send-ready, opt-in telemetry, content-free crash reporting | `core/metrics/**` (new), `app/settings/**` | M |
| W0.4 | Performance and size gates measured; assign a standing owner to `core/text/` | `app/build.gradle.kts`, `docs/PERFORMANCE_REVIEW.md`, `core/text/**` | M |

**W0.2 is the plan's most important reordering.** Draft rescue was ranked sixth on the
feature list. It is not a feature — it is a data-loss bug whose existence is already
recorded as a `TODO` in `finishSession()` saying that an input connection which dies
before the final pass returns loses the user's speech. It belongs in the bug queue, above
every V2 item.

**W0.3 is the precondition for the metric ruling in §4 being real rather than
aspirational.**

Concurrency: W0.3 ∥ W0.4 can run together, and both are disjoint from Wave 1.5's `core`
work. W0.1 and W0.2 both want `VBoardImeService.kt` and are strictly sequential —
**W0.2 first**, because it is the bug.

### Wave 0.5 — Process split (decided)

**The LLM refiner moves out of the keyboard process.** Decided; not yet scheduled
against a wave slot, but it belongs before beta because it is the one change that
alters the crash surface rather than the feature set.

Why it matters, from [PERFORMANCE_REVIEW.md §7](PERFORMANCE_REVIEW.md): the IME
process today also hosts Compose, settings, onboarding and the downloader. A
0.5B model loaded into that process means the keyboard's memory ceiling is set by
the refiner, and a native OOM or a MediaPipe crash inside it takes the keyboard
down mid-sentence — in every app on the device, not just ours. An IME is not an
app the user can decide to reopen; when it dies they lose the ability to type.

The split also makes the ≤60MB typing-only budget meaningful for the first time,
since it stops being a number that a background feature can blow through.

**Scope:** `android:process=":llm"` for the refiner plus a binder interface, and
`android:process=":ui"` for the activities and downloader (the second half of the
same change, and it is the cheaper half). WorkManager's auto-init ContentProvider
comes out of the keyboard process with them.

**Hazard to respect:** the refiner is currently called in-process and synchronously
from the cleanup path. Crossing a process boundary makes it genuinely asynchronous
and genuinely failable, so the "replacement only when the user hasn't edited" rule
and the watchdog fallback both need re-checking against a refiner that can now
die independently — that is the actual work, not the manifest entries.

### Wave 1.5 — Text-core correctness (the QA findings)

Everything in [QA_REPORT.md](QA_REPORT.md) §3, packaged. These are **defects, not
features** — no user asked for them and no user will thank us for them, but four of them
lose the user's words outright and two cross a privacy boundary we stated publicly.

| # | Item | Closes | Owns | Size |
|---|---|---|---|---|
| W1.5.1 | **Package A — Unicode-safe text core** ✅ landed | VB-QA-12, -13, -14, -15, -16, -17, -21, **-27** | `core/text/Tokens.kt`, `TranscriptCleaner.kt` (`ARTIFACT_REGEX` + its call site only), `Cleanup.kt` | M |
| W1.5.2 | **Package B — Destructive-stage confidence + field-kind honesty** ✅ landed | VB-QA-18, -19, -20, -29, **-30**, **-31**, gap G3 | `core/text/TranscriptCleaner.kt` (stages 2/3/5, `capitalize`), `FieldKind.kt`, `Cleanup.kt` | M |
| W1.5.3 | **Package C — Seams: commit planning, clipboard privacy, suggestion ranking** ✅ landed | VB-QA-22, -23, -24, -25, -26, -28, -32, **-33, -34** | `core/text/CommitPlanner.kt`, `core/clipboard/ClipClassifier.kt`, `core/suggest/SuggestionEngine.kt`, `core/correct/ContentGuard.kt` | M |

**Wave 1.5 is complete.** 28 `@Disabled` tests were written and waiting: Package
A enabled 11, Package B 7 and Package C 10, which is all of them. The core suite
on the merged branch is **762 tests, 0 failures, 1 skipped** — the one skip is
`CleanupPropertyTest`'s VB-QA-05 case, which was never part of this wave. Each
disabled test named the `VB-QA-NN` it was
blocked on; each package's definition of done is "these specific tests pass with the
annotation removed, and the invariant suites stay green". That last clause is the real
gate — `CleanupInvariantQaTest` and the clipboard retention fuzz exist to catch a fix that
overshoots, and a package that closes its own tests while breaking theirs is not done.
Nobody has to re-derive the requirements; the spec is executable.

Sequencing inside the wave: **A first, then B and C in any order or concurrently.**
That is exactly how it ran — A landed on `main`, then B and C were built
concurrently in separate worktrees and merged onto `wave15-packages-bc` (B is
`d606879`, C is `00fbd0d`) with no production-code conflict. The claim was that A is
not merely largest but *shrinks the other two* — VB-QA-33 and VB-QA-34 exist only because
`ContentGuard` is compensating for `Tokenizer`, and gap G2 becomes much smaller once the
tokenizer stops destroying content. Only half of that held: after A, the shield no longer
changes the outcome for the inputs `TypedTextSafetyQaTest` compares — but VB-QA-33 and
-34 are defects in `ContentGuard.needsShield` itself, both still failed with their
`@Disabled` annotation removed, and C had to fix them there, which is why
`ContentGuard.kt` appears in its `Owns` cell above. Full file ownership, the constraints each fixer must
not violate, and per-package definitions of done are in [QA_REPORT.md §9](QA_REPORT.md).

**The ordering question that was open here is now closed: bugs first, features
later.** Wave 1.5 ran *before* Wave 1. That resolved the file collision it would
otherwise have had — W1.2 (spoken-format intelligence) lists `core/text/Tokens.kt`
among the files it owns, which is the file Package A rewrote. Building `4:30pm`
and `$25` handling on a tokenizer that still deleted `:` and `$` would have meant
writing the feature against behaviour about to change underneath it. **That gate
is now satisfied**: W1.2 inherits a tokenizer that already preserves the
characters it needs, and the feature is smaller rather than reworked.

Running W1.2 and Package A concurrently would have been the one option that does
not work: one file, two owners, and this project has already lost an agent's work
to exactly that.

**Two of these were not schedulable as ordinary polish, and both are now closed.**
VB-QA-24 wrote a one-time code or card number to disk when it arrived in
non-ASCII digits — `SESSION_ONLY` is the privacy boundary and this crossed it.
**Package C closed it**, together with VB-QA-26 in the same change as the
constraint required, and closed an unnumbered zero-width-character variant of the
same crossing found in the same audit ([QA_REPORT.md §9](QA_REPORT.md)).
VB-QA-29 capitalized inside `PASSWORD` fields, which the spec says must be left
untouched entirely; **Package B closed it** by gating `capitalize()` on
`FieldKind.allowsAutoCapitalize` at every position. Nothing in this wave now
needs lifting out ahead of beta. Two narrower holes on the same privacy boundary
remain open and carry no VB-QA id — `ClipClassifier`'s payment-card rule still
reads raw text, and `DIGIT_RUN_PATTERN`'s separator class is still ASCII-only;
see [QA_REPORT.md §9](QA_REPORT.md), "Not in any package". `README.md` still
discloses VB-QA-24 and VB-QA-29 as live and needs correcting before beta.

### Wave 1 — Confidence foundation

| # | Item | Owns | Size |
|---|---|---|---|
| W1.1 | Transcript normalization + two-model alignment + per-word confidence | `core/confidence/**` (new), `core/session/FinalTranscriptPolicy.kt` | L |
| W1.2 | Spoken-format intelligence (times, money, dates, spoken email addresses) | `core/format/**` (new), `core/text/Tokens.kt` | M |

One owner, sequential — W1.2 consumes W1.1's normalizer. Pure `core`, no Android, no file
contention with Wave 0. W1.2 touches `core/text/Tokens.kt`, which Wave 1.5 rewrites first —
so W1.2 must not start until W1.5.1 has landed.

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
bar. Handed to W0.1 as an input.

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

**1. Does V2 start before or after beta? — DECIDED: bugs first, features later.**
Wave 0 and Wave 1.5 now, Wave 1 concurrent with beta, Waves 2–3 after first user data.
Wave 1.5 was never really a V2 question — it is fixing V1. Everything past Wave 1 is a
guess until someone outside the team has used the keyboard.

**2. Ordering between Package A and W1.2 — DECIDED**, by the same ruling: Wave 1.5 runs
in full before Wave 1, so `core/text/Tokens.kt` has one owner at a time.

**3. Do we accept that the confidence idea's value is unproven until measured?**
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
