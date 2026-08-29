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

**Wave 1.5 Package B has landed** (uncommitted on branch `wave15-package-b` at
the time of writing): the destructive cleanup stages now require corroborating
evidence before they delete words, and field-kind gating is honoured at every
position — closing VB-QA-18, -19, -20, -29, -30, -31 and gap G3.

**Wave 1.5 Package C is in flight** in a separate worktree. It owns
`CommitPlanner.kt`, `ClipClassifier.kt`, `SuggestionEngine.kt` and
`ContentGuard.kt`; nothing below assumes any of it has landed.

**Core suite: 761 tests, 0 failures, 11 skipped** (was 18 after Package A, 29
before it). Every skip is a `@Disabled` test asserting spec-correct behaviour and
naming the `VB-QA-NN` defect that blocks it. They are the executable spec for the
work below — not coverage gaps. The 11 are 3 in `CommitSeamQaTest`, 3 in
`ClipboardPrivacyQaTest`, 2 in `SuggestionFieldMatrixQaTest` and 2 in
`TypedTextSafetyQaTest` — all Package C — plus 1 in `CleanupPropertyTest`
(VB-QA-05), which is outside this wave.

## What to build, in order

Read [`docs/V2_PLAN.md`](V2_PLAN.md) §3 for the waves and
[`docs/QA_REPORT.md`](QA_REPORT.md) §9 for per-package file ownership,
constraints and definitions of done. The ruling is **bugs first, features
later**, so:

1. ~~**Wave 1.5, Package A — Unicode-safe text core.**~~ ✅ done.
2. ~~**Wave 1.5, Package B — destructive-stage confidence and field-kind
   honesty.**~~ ✅ done.
3. **Wave 1.5, Package C — seams.** In flight in a separate worktree; it is the
   only Wave 1.5 work left. Everything still skipped in `:core` except VB-QA-05
   is C's.
4. Wave 0.5 — move the LLM refiner out of the keyboard process (decided).
5. Wave 0 items, then Wave 1.

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

### Package B in one paragraph — what it was, and what landed

Three cleanup stages deleted the user's words on surface form alone: spoken
punctuation was substituted whenever the preceding token was not a determiner
(and multi-word phrases like `full stop` had no guard at all), `scratch that`
mid-sentence cut back to the start of the utterance, and `no wait` fired as a
correction marker at index 0 where every other marker required `i > 0`. On top of
that, `FieldKind.allowsAutoCapitalize` gated only the *first* word, so cleanup
capitalized inside `PASSWORD` fields, and no `CleanupResult` counter reported
that any substitution had happened.

The fix in each case was to require corroborating evidence rather than a wider or
narrower word list. VB-QA-20 gave `no wait`/`wait no` the same `i > 0` guard as
the rest of the marker table. VB-QA-19 **demoted** `scratch`/`strike` + `that`
from a strong scratch marker to a weak one, so it acts only when `findAlignment`
succeeds; the `isScratch` branch, `Marker.isScratch` and `clauseStartBefore`
became dead code and were removed. VB-QA-18 replaced the single determiner guard
with three cheap signals — never convert at index 0; the determiner guard now
covers multi-word phrases too; and a *sentence-splitting* conversion (`.` `?` `!`)
must be utterance-final, followed by a break or another punctuation phrase, or
followed by ≥ 2 words. Inline marks (`,` `:` `;` `-` `&`) and break conversions
are governed by the first two signals only, and `hashtag` left the ambiguous
single-word set. VB-QA-29 put the whole of `capitalize()` behind
`request.fieldKind.allowsAutoCapitalize`, so cleanup no longer transforms
`PASSWORD` content at all — that is the one with the privacy story. G3 added
`CleanupResult.spokenSubstitutions`, incremented once per accepted substitution;
it counts only, never records what was substituted, and is 0 when
`spokenCommands = false`.

Five things a reader should know about how it landed:

- **VB-QA-30 was fixed inside `normalizePunctuationSequence`, not in
  `Tokenizer.render`** — adjacent `Tok.Break` runs are merged before rendering.
  The `@Disabled` message pointed at `Tokens.kt`, which is Package A's file;
  it was deliberately not touched. `FieldKind.kt` needed no change either, so
  Package B's actual footprint is `TranscriptCleaner.kt`, `Cleanup.kt` and four
  QA test files.
- **VB-QA-31's ellipsis absorb is local too.** `"..."` absorbs a following comma
  or period inside `normalizePunctuationSequence` only. It was deliberately *not*
  added to `SENTENCE_ENDERS`, because that set is also read by `capitalize`,
  `findAlignment` and `clauseStartBefore`; adding it there broke the golden case
  `one more thing ellipsis the demo needs music` and VB-QA-31's own lowercase-`and`
  expectation.
- **The golden corpus has zero diff.** All 53 cases and 5 standalone regressions
  are unchanged, 58/58 pass — no golden case changed in this package.
  `CleanupInvariantQaTest` is 21/21 and the privacy audit passes with no
  violations.
- **The VB-QA-05 pin was flipped, not deleted.** `QaRegressionPinTest`'s
  `VB-QA-05 idempotency holds everywhere except the three documented inputs` is
  now `...except the one documented input`: `new line new line new line` closes
  via VB-QA-30's break merge and `no wait no wait no wait no wait no wait` via
  VB-QA-20's `i > 0` guard. `scratch that scratch that` still breaks, because
  re-cleaning it hits `detectUtteranceCommand`. `CleanupPropertyTest`'s VB-QA-05
  test stays correctly `@Disabled` and out of scope. The pinned id list is still
  exactly `[1,2,3,4,5,6,7,9,10,11,12]`.
- **One `@Disabled` test's expectation was changed by supervisor ruling.**
  `SpokenCommandSafetyQaTest`'s `a converted symbol should be spaced and never
  silently discarded` asserted `use hashtag now` → `Use # now.` and `at sign up
  time` → `@ up time.`. Both outputs are two-word utterances, below
  `MIN_WORDS_FOR_TERMINAL_PERIOD = 3`, so the trailing period was unreachable;
  worse, its `use hashtag now` expectation contradicted its sibling `an ordinary
  sentence containing a punctuation word should survive`, which asserts
  `Use hashtag now.` for the same input through the same pure function. It was
  neither deleted nor left disabled: its intent — *a conversion must be
  well-formed and must never silently discard the user's words* — was preserved
  and its assertions rewritten against reachable inputs.

Six pinned tests asserted the exact opposite of their new bodies and were
renamed; anything quoting the old names is stale. In `SpokenCommandSafetyQaTest`:
`...not preceded by a determiner is converted` → `a punctuation word is converted
only where the context supports it`; `scratch that mid-sentence deletes
everything before it` → `...only acts when it aligns`; `no wait at the start of
an utterance is treated as a correction marker` → `...is content, not a marker`;
and `a spoken command substitution is not reported by any counter` → `...is
reported by its own counter` (the G3 gap it pinned is closed). In
`CleanupInvariantQaTest`: `fields that disallow auto-capitalization still
capitalize after a period or break` → `...are left alone at every position`;
`three or more consecutive breaks are emitted and then collapse on re-clean` →
`consecutive breaks are merged before rendering`; `an ellipsis does not absorb a
following period or comma` → `an ellipsis absorbs an adjacent period or comma`.

## One defect that jumps the queue if beta ships first

- **VB-QA-24** — the clipboard classifier detects digit runs with ASCII-only
  `\d`, so a one-time code or card number in Arabic-Indic, Devanagari, Persian or
  full-width digits is written to the clipboard history file instead of held in
  memory for 60 seconds. Crosses a stated privacy boundary. Must be fixed in the
  same change as VB-QA-26, or a false negative becomes a false positive. It is
  Package C's, and still open.

VB-QA-29 — cleanup capitalizing inside `PASSWORD` fields — was the other one, and
is now **closed by Package B**: `capitalize()` is gated on
`FieldKind.allowsAutoCapitalize` at every position. Both were disclosed in the
README's privacy section rather than only in the audit; that disclosure now
overstates the exposure by one item.

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
deleted, when the defect is closed. Package B flipped seven more on the same
rule (six in `SpokenCommandSafetyQaTest`/`CleanupInvariantQaTest`, plus the
VB-QA-05 pin); see "Package B in one paragraph" above for the old and new names.

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
