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

**Wave 1.5 Package C has landed** (uncommitted on branch `wave15-package-c`):
the commit seam, the clipboard classifier, the suggestion ranker and
`ContentGuard` now classify by Unicode code point instead of by ASCII
assumption, closing VB-QA-22, -23, -24, -25, -26, -28, -32, -33 and -34.

**Core suite: 762 tests, 0 failures, 8 skipped** (761 tests and 18 skips before
Package C; 29 skips before Package A). Package C removed ten `@Disabled`
annotations and added one regression test, which is the whole of the +1. Every
remaining skip is a `@Disabled` test asserting spec-correct behaviour and naming
the `VB-QA-NN` defect that blocks it. They are the executable spec for the work
below — not coverage gaps. All eight are outside Package C:
`SpokenCommandSafetyQaTest` (4 — VB-QA-18 twice, -19, -20),
`CleanupInvariantQaTest` (3 — VB-QA-29, plus the cosmetic VB-QA-30 and -31,
which are in no package) and `CleanupPropertyTest` (1 — VB-QA-05).

## What to build, in order

Read [`docs/V2_PLAN.md`](V2_PLAN.md) §3 for the waves and
[`docs/QA_REPORT.md`](QA_REPORT.md) §9 for per-package file ownership,
constraints and definitions of done. The ruling is **bugs first, features
later**, so:

1. ~~**Wave 1.5, Package A — Unicode-safe text core.**~~ ✅ done.
2. ~~**Wave 1.5, Package C — seams: commit planning, clipboard privacy,
   suggestion ranking.**~~ ✅ done.
3. **Wave 1.5, Package B — destructive-stage confidence and field-kind
   honesty** — start here; it is the last of the wave.
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

### Package C in one paragraph — what it was, and what landed

Three seams — `CommitPlanner`, `ClipClassifier`, `SuggestionEngine` — plus
`ContentGuard` each made a *classification* decision about a single character or
token, and all four made it by ASCII assumption: `\d` where the code meant
"digit", a literal `'$'` where it meant "currency", `precedingText[length - 2]`
where it meant "the previous character the user sees". The cost ranged from
cosmetic (a leading space before a closing curly quote) to a stated privacy
boundary crossed — a one-time code in Arabic-Indic digits classified `NORMAL`
and written to the clipboard history file instead of held in memory for 60
seconds. All 10 `@Disabled` tests are enabled and green, and the overshoot
guards are green with them: `CleanupInvariantQaTest` 21/0 (its 3 skips are
Package B's), the 500-step clipboard retention fuzz, `QaRegressionPinTest` 12/0
(including the VB-QA-06 and VB-QA-09 pins), `SuggestionFieldMatrixQaTest`'s
18,000-case strip invariants, `CommitSeamQaTest`'s 4,000-pair diff
reconstruction fuzz and `TypedTextSafetyQaTest`'s 4,000-case content-loss fuzz.
`CleanupGoldenCorpusTest` (58 tests) is untouched and green: no golden case
changed.

How each fix was actually made — the shape matters more than the id:

- **VB-QA-22/-23** — decide on the *code point* at the seam, not on a UTF-16
  `Char`; currency is recognized by general category `Sc` rather than by a
  literal `'$'`; `doubleSpacePeriodApplies` walks back over combining marks to
  the base character.
- **VB-QA-28** — `TextDiff.replacement` rounds the kept prefix down to a
  grapheme-cluster boundary. **`java.text.BreakIterator` was tried and
  rejected**: on the pinned JDK 17 toolchain its character instance implements
  legacy clusters and splits ZWJ sequences, regional-indicator flag pairs and
  emoji skin-tone modifiers — precisely the inputs the fix exists for. The
  back-off is hand-rolled. Do not repeat the experiment.
- **VB-QA-24/-26** — fixed in one change, as the plan required. "Digit" now
  means general category **Nd** consistently across `OTP_PATTERN`,
  `DIGIT_RUN_PATTERN`, the digit extraction and the Luhn arithmetic
  (`Character.digit(cp, 10)` replaces `digits[i] - '0'`), iterating code points
  so astral digits count.
- **VB-QA-25** — a shared code-point predicate treats Cf/Cc as invisible, so a
  visually empty clip is `Discard(BLANK)`. U+2800 BRAILLE PATTERN BLANK is
  deliberately *not* blank.
- **VB-QA-32** — the natural lever (charging a non-ASCII↔ASCII substitution a
  blocking edit cost) lives in `Lexicon.fuzzyDescend`, which Package C did not
  own. Instead one predicate in `SuggestionEngine` — the token contains a letter
  outside `a-z` — skips fuzzy matching and gates autocorrect. `LITERAL_PRIOR`
  was **not** retuned, so ASCII ranking is unmoved and VB-QA-06 and -09 are
  untouched.
- **VB-QA-33** — a combining mark counts as prose when it follows a letter, so
  NFD `café` is no longer shielded and no longer escapes sentence casing; a
  leading mark, or a mark after an emoji, still shields.
- **VB-QA-34** — an edge hyphen shields **only when the core also contains a
  letter or a digit**. That clause is load-bearing: shielding a bare `-` bullet
  would move `first` off the sentence start and turn `- first item` into
  `- First item`.

Four things a reader should know about how it landed:

- **The package owned four production files, not the three the plan named.**
  VB-QA-33 and -34 live in `ContentGuard.needsShield`
  (`core/src/main/kotlin/com/vboard/core/correct/ContentGuard.kt`) and their
  tests in `TypedTextSafetyQaTest`, so `ContentGuard.kt` came with them — which
  is also why the count is 10 disabled tests and not the 9 written down. Eight
  files changed in all; `QaRegressionPinTest.kt`, `ClipClassifierTest.kt`,
  `CommitPlannerTest.kt`, `ContentPreservationTest.kt` and `ClipboardHistory.kt`
  are unmodified.
- **VB-QA-33 and -34 did not disappear after Package A**, contrary to the hedge
  in earlier drafts of this document and of `QA_REPORT.md` §9. What is true is
  narrower, and was checked this cycle: `TypedTextSafetyQaTest`'s
  guarded/unguarded comparison pairs are now identical, so the *shield* no
  longer changes the outcome for those inputs — but both disabled tests still
  genuinely failed with the annotation removed, and both needed real
  `ContentGuard` changes. Settled; it does not need re-litigating.
- **Ten regression pins were flipped, not deleted.** Each disabled test had a
  neighbouring `(pinned)` test asserting the *unfixed* behaviour — 3 in
  `CommitSeamQaTest`, 3 in `ClipboardPrivacyQaTest`, 2 in
  `SuggestionFieldMatrixQaTest`, 2 in `TypedTextSafetyQaTest`. All were inverted
  to assert the fix and renamed; none was deleted. Same precedent as Package A's
  VB-QA-12 pin. `QaRegressionPinTest` itself was **not** touched — it holds no
  pin for any Package C id, its ids stopping at 12.
- **One extra defect was found and closed beyond the nine ids, and it has no
  VB-QA id.** A privacy audit caught that the fixed OTP rule still trimmed with
  `String.trim()`, which does not strip Cf format characters — so a one-time
  code carrying a zero-width space or a BOM (`"<U+200B>123456"`, and the
  Arabic-Indic equivalent) was classified `NORMAL` and **persisted to disk**: the same
  `SESSION_ONLY` crossing as VB-QA-24, one layer out. Both predicates now share
  a single private `isInvisible` helper so they cannot drift apart again, and
  `ClipboardPrivacyQaTest.a one-time code wearing an invisible character is
  still session-only` pins it. It is deliberately left unnumbered rather than
  given a VB-QA id.

Two limitations knowingly left open, so they are not rediscovered as surprises:

- `DIGIT_RUN_PATTERN`'s separator class is still ASCII-only (`[ -]?`), so a card
  number grouped with NBSP, U+2007 or an en-dash still evades the Luhn check and
  is persisted. Deliberately deferred.
- `ContentGuard.Shield`'s privacy protection rests on a convention: it is a
  plain class with a hand-written count-only `toString`, deliberately **not** a
  `data class`, so shielded user content cannot reach a log. Adding `data`, or
  accepting an IDE-generated `toString`, would silently defeat it and no test
  would catch it.

## The defect that jumps the queue if beta ships first

- **VB-QA-29** — cleanup capitalizes inside `PASSWORD` fields; the field-kind
  flag gates only the first word.

VB-QA-24 stood here until this cycle — a one-time code or card number in
Arabic-Indic, Devanagari, Persian or full-width digits written to the clipboard
history file instead of held in memory for 60 seconds. Package C closed it,
together with VB-QA-26 in the same change as the constraint required, and closed
the unnumbered zero-width variant above in the same audit. Both defects were
disclosed in the README's privacy section rather than only in the audit; that
disclosure now describes a fixed defect where VB-QA-24 is concerned and wants
revisiting when this branch merges (out of scope for this document).

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
deleted, when the defect is closed. Package C flipped ten more pins the same way,
inside the four QA suites it owned, and left `QaRegressionPinTest` alone because
it holds no pin for any of Package C's ids.

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
