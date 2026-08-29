# VBoard — handoff for a fresh session

Date: 2026-08-29. Written to be read cold. Everything below is verified against
the repo, not remembered.

## Where things stand

PR #1 is **merged**; `main` carries the full V1 implementation (~84k lines, 153
files). Branch `claude/android-voice-keyboard-whisper-eekm0g` was restarted from
the merged `main` and carries one unmerged commit (decision records + README
corrections). CI is green: core unit tests, Android debug build + lint + app
tests, and the R8 release build.

**Wave 1.5 Package A has landed** and is on `main` (commit `0b243b8`, carried by
`a478ad4`, which this branch is taken off): the tokenizer now iterates code points under a deny-list, closing
VB-QA-12, -13, -14, -15, -16, -17, -21 and -27.

**Wave 1.5 Packages B and C have both landed.** They were built concurrently in
separate worktrees and are merged onto branch `wave15-packages-bc`, taken off
`main` at `a478ad4`: Package B is commit `d606879`, Package C is `00fbd0d`.

Package B made the destructive cleanup stages require corroborating evidence
before they delete words, and made field-kind gating hold at every position —
closing VB-QA-18, -19, -20, -29, -30, -31 and gap G3. Package C made the commit
seam, the clipboard classifier, the suggestion ranker and `ContentGuard` classify
by Unicode code point instead of by ASCII assumption — closing VB-QA-22, -23,
-24, -25, -26, -28, -32, -33 and -34.

**Wave 1.5 is complete.** A, B and C have all landed; nothing in the wave
remains.

**Core suite: 762 tests, 0 failures, 1 skipped** (761 tests · 18 skips after
Package A; 11 after Package B; 29 skips before Package A). Package B removed 7
`@Disabled` annotations and Package C removed 10; Package C also added one
regression test, which is the whole of the 761 → 762. No golden case changed —
`CleanupGoldenCorpusTest.kt` was modified by neither package.

The single remaining skip is `CleanupPropertyTest`'s **VB-QA-05** (idempotency),
which was never part of this wave. **Every other `@Disabled` in `:core` is
gone**, so the disabled-test spec that drove Wave 1.5 no longer exists; from here
the suite is a regression net, not a to-do list.

## What to build, in order

Read [`docs/V2_PLAN.md`](V2_PLAN.md) §3 for the waves and
[`docs/QA_REPORT.md`](QA_REPORT.md) §9 for per-package file ownership,
constraints and definitions of done. The ruling is **bugs first, features
later**, so:

1. ~~**Wave 1.5, Package A — Unicode-safe text core.**~~ ✅ done.
2. ~~**Wave 1.5, Package B — destructive-stage confidence and field-kind
   honesty.**~~ ✅ done.
3. ~~**Wave 1.5, Package C — seams: commit planning, clipboard privacy,
   suggestion ranking.**~~ ✅ done. B and C were built concurrently in separate
   worktrees and are merged here. **Wave 1.5 is closed** — the only skip left in
   `:core` is VB-QA-05, which was never part of it.
4. **Wave 0.5 — move the LLM refiner out of the keyboard process** (decided).
   This is the next thing to build.
5. Wave 0 items, then Wave 1. W1.2 (spoken-format intelligence) was gated on
   Package A landing, because both own `core/text/Tokens.kt`; that gate is now
   satisfied.

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
guards are green with them: `CleanupInvariantQaTest` 21 tests, 0 failures (3 of
the 21 were still skipped for Package B while C was built in its own worktree;
Package B closed those three, so on the merged branch it is 21/0), the 500-step clipboard retention fuzz, `QaRegressionPinTest` 12/0
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

Three limitations knowingly left open, so they are not rediscovered as surprises:

- `DIGIT_RUN_PATTERN`'s **separator** class is still ASCII-only (`[ -]?`), so a
  card number grouped with NBSP, U+2007 or an en-dash still evades the Luhn check
  and is persisted. The digit class was internationalized; the separator class
  was not. Deliberately deferred.
- **`ClipClassifier` strips invisible code points before the OTP test but passes
  raw text to the payment-card test**, so a card number carrying a zero-width
  character still evades Luhn and reaches disk. This is the same `SESSION_ONLY`
  crossing as VB-QA-24, one rule over, and it is the sibling of the unnumbered
  OTP defect closed above — the audit fixed one rule and not its neighbour. It
  has **no VB-QA id**; do not invent one for it.
- `ContentGuard.Shield`'s privacy protection rests on a convention: it is a
  plain class with a hand-written count-only `toString`, deliberately **not** a
  `data class`, so shielded user content cannot reach a log. Adding `data`, or
  accepting an IDE-generated `toString`, would silently defeat it and no test
  would catch it. A `toString`-shape assertion would make that loud; there isn't
  one.

## The two defects that would have jumped the queue — both now closed

Two findings crossed a stated privacy boundary and were marked to be lifted out
of the wave if beta shipped before Wave 1.5 finished. Neither needs lifting now.

- **VB-QA-24** — the clipboard classifier detected digit runs with ASCII-only
  `\d`, so a one-time code or card number in Arabic-Indic, Devanagari, Persian or
  full-width digits was written to the clipboard history file instead of held in
  memory for 60 seconds. **Closed by Package C**, together with VB-QA-26 in the
  same change as the constraint required, plus the unnumbered zero-width variant
  found in the same audit.
- **VB-QA-29** — cleanup capitalized inside `PASSWORD` fields; the field-kind
  flag gated only the first word. **Closed by Package B**: `capitalize()` is now
  gated on `FieldKind.allowsAutoCapitalize` at every position, so cleanup does
  not transform `PASSWORD` content at all.

**`README.md` still discloses VB-QA-24 and VB-QA-29 as live defects in its
privacy section.** Both are closed; that disclosure is now wrong on both counts
and needs a one-line correction before beta. It was out of scope for this
branch's documentation work and has **not** been made — it is left as a task.

The boundary is not fully sealed, though: the payment-card rule in
`ClipClassifier` still reads raw text, so a card number carrying a zero-width
character reaches disk. It is unnumbered and recorded above under Package C's
limitations, not as a new VB-QA id.

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
VB-QA-05 pin), and Package C flipped ten more inside the four QA suites it
owned. Package C deliberately left `QaRegressionPinTest` itself alone, because it
holds no pin for any of Package C's ids — its ids stop at 12. See the two "in one
paragraph" sections above for the old and new names.

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
