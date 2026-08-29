# VBoard — handoff for a fresh session

Date: 2026-08-29. Written to be read cold. Everything below is verified against
the repo, not remembered.

## Where things stand

PR #1 is **merged**; `main` carries the full V1 implementation (~84k lines, 153
files). Branch `claude/android-voice-keyboard-whisper-eekm0g` was restarted from
the merged `main` and carries one unmerged commit (decision records + README
corrections). CI is green: core unit tests, Android debug build + lint + app
tests, and the R8 release build.

**Wave 1.5 Package A has landed** (uncommitted on that branch at the time of
writing): the tokenizer now iterates code points under a deny-list, closing
VB-QA-12, -13, -14, -15, -16, -17, -21 and -27.

**Core suite: 761 tests, 0 failures, 18 skipped** (was 29 before Package A).
Every skip is a `@Disabled` test asserting spec-correct behaviour and naming the
`VB-QA-NN` defect that blocks it. They are the executable spec for the work
below — not coverage gaps.

## What to build, in order

Read [`docs/V2_PLAN.md`](V2_PLAN.md) §3 for the waves and
[`docs/QA_REPORT.md`](QA_REPORT.md) §9 for per-package file ownership,
constraints and definitions of done. The ruling is **bugs first, features
later**, so:

1. ~~**Wave 1.5, Package A — Unicode-safe text core.**~~ ✅ done.
2. **Wave 1.5 Packages B and C** — start here; safe to run concurrently.
3. Wave 0.5 — move the LLM refiner out of the keyboard process (decided).
4. Wave 0 items, then Wave 1.

### Package A in one paragraph — what it was, and what landed

`core/src/main/kotlin/com/vboard/core/text/Tokens.kt` iterated UTF-16 `Char`s and
kept a character only when `Char.isLetterOrDigit()` was true or it appeared in a
13-character punctuation allow-list. The `else` branch called `flushWord()`, so an
unrecognized character was not merely deleted — it also inserted a word boundary,
splitting the word it sat inside. That destroyed 88,833 of 138,552 named Unicode
code points (64%): every emoji, every combining mark, every currency and math
symbol. Devanagari and Thai were unwritable, and NFD `café` de-accented while its
NFC twin survived, so output depended on a normalization form no ASR engine
guarantees.

**The fix was not a wider allow-list** — that would have to enumerate 63 currency
signs, 948 math symbols and 24 dashes and still leave combining marks and the
entire astral plane broken. It was a policy inversion: **iterate code points, and
go from allow-list-keep to deny-list-drop**, where the deny-list is only the small
closed set of ASR artifacts (Cc/Cs/Cn plus BOM and U+FFFD). All 11 `@Disabled`
tests are enabled and green; `CleanupInvariantQaTest` is still green, which is the
guard against a fix that preserves *too much*.

Three things a reader should know about how it landed:

- **The golden corpus has 53 cases plus 5 standalone regression tests** (58 tests
  in `CleanupGoldenCorpusTest`), not 44 — the "44" in earlier drafts of this
  document and of `QA_REPORT.md` was stale. Exactly one case changed:
  `that jacket costs $75` now keeps its `$`, which *is* the VB-QA-12 fix.
- **`sentenceStartsAt` was taken by Package A**, not deferred to B, because
  VB-QA-27's test sits in `UnicodeSafetyQaTest`. Package B inherits it fixed.
  A straight `'` is deliberately *not* a sentence closer — it is ambiguous with a
  word-final apostrophe.
- **Two additions beyond the plan.** The non-raw path normalizes to NFC (raw mode
  is exempt — normalization is itself a transformation); and structural
  punctuation with a word character on both sides is treated as intra-word, so
  `a_b@c.com` and `well-known` stay one word.

## Two defects that jump the queue if beta ships first

- **VB-QA-24** — the clipboard classifier detects digit runs with ASCII-only
  `\d`, so a one-time code or card number in Arabic-Indic, Devanagari, Persian or
  full-width digits is written to the clipboard history file instead of held in
  memory for 60 seconds. Crosses a stated privacy boundary. Must be fixed in the
  same change as VB-QA-26, or a false negative becomes a false positive.
- **VB-QA-29** — cleanup capitalizes inside `PASSWORD` fields; the field-kind
  flag gates only the first word.

Both are now disclosed in the README's privacy section rather than only in the
audit.

## Hard constraints

- **Never log user content.** No transcript text, clipboard content, keystrokes,
  field text, character counts of content, or content-derived trace/counter
  names. Trace and counter names must be compile-time constants.
- `:core` is pure JVM and testable locally: `./gradlew -Pvboard.skipAndroid=true :core:test`.
  `:app` needs the Android SDK — on a machine without it, use the skip flag and
  let CI build the app module.
- Parakeet is **mandatory**, not optional. Streaming-only is not an acceptable
  typing experience; this was reversed once already.
- Endpointing lives **entirely** on the streaming recognizer —
  `OfflineRecognizerConfig` has no endpointing at all. The 20M model is a
  subsystem (endpointing, liveness, watchdog fallback, second opinion), not a
  download line item.
- Never fabricate a SHA-256 digest for a host you cannot reach. The Qwen refiner
  pack is deliberately unpinned for exactly this reason.

## Fixes already made that must not regress

`QaRegressionPinTest` asserts one test per VB-QA-01…12 and will fail loudly if
any of these come back. Note that its VB-QA-12 pin originally asserted the
*unfixed* state (the `$` still being dropped); Package A flipped it to assert
`That jacket costs $75.` — a pin that pins an open defect must be flipped, not
deleted, when the defect is closed.

- Number-like words are exempt from repetition collapse, so dictated phone
  numbers survive (VB-QA-01).
- Internal capitals gate autocorrect: `iPhone`, `iOS`, `VBoard` (VB-QA-06).
- A per-pack `Mutex` serializes installs so two concurrent downloads cannot
  interleave into one `.part` (VB-QA-07).
- The typed literal is forced into the suggestion strip when ranking drops it
  (VB-QA-09).
- The installer gates on the server's authoritative `Content-Length`, not the
  catalog estimate, and treats HTTP 416 as "already complete" (VB-QA-10).
- `VirtualCells.sendEvent` checks `isEnabled()` before
  `requestSendAccessibilityEvent` — the framework throws from there when no
  service is listening, which took the keyboard down mid-sentence.

## Still open, for the human to decide

- Does the two-model confidence signal (Wave 1) survive its gate? It needs a
  hand-labeled corpus of ≥200 utterances that nobody has built yet. Below ~70%
  disagreement precision, everything in Wave 3 that consumes it is **cancelled,
  not deferred**.
- Silence-timeout value — still unresolved; W2.1 hold-to-talk would retire the
  question for the common case.
- Does lint get `abortOnError = true`? CI lint currently cannot fail the build.
