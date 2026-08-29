# VBoard QA Report

Date: 2026-08-29 · Scope: `core` module (fully machine-verified) + manual test
plan for the Android layer. Requirement IDs (VB-###) refer to
[PRODUCT_SPEC.md](PRODUCT_SPEC.md).

Status: **findings captured. No fix work is dispatched** — see
[§7 Standing work packages](#7-standing-work-packages). Companion documents:
[V2_PLAN.md](V2_PLAN.md) · [V2_PROPOSALS.md](V2_PROPOSALS.md) ·
[PERFORMANCE_REVIEW.md](PERFORMANCE_REVIEW.md)

## 1. Test suite summary

**762 tests · 0 failures · 8 skipped.** Every skip is a `@Disabled`
spec-correct assertion naming the `VB-QA-NN` it is blocked on, not a coverage
gap.

| Area | Test classes | Status |
|---|---|---|
| Transcript cleanup (unit) | `TranscriptCleanerTest` | ✅ pass |
| Cleanup golden corpus | `qa/CleanupGoldenCorpusTest` (53 cases + 5 regressions) | ✅ pass |
| Cleanup properties | `qa/CleanupPropertyTest` | ✅ 1 skip (VB-QA-05) |
| Cleanup invariants (generative, ~55k cases) | `qa/CleanupInvariantQaTest` | ✅ 3 skips |
| Unicode safety | `qa/UnicodeSafetyQaTest` | ✅ pass |
| Tokenizer symbol loss | `qa/TokenizerSymbolLossQaTest` | ✅ pass |
| Spoken-command safety | `qa/SpokenCommandSafetyQaTest` | ✅ 4 skips |
| Raw-mode fidelity | `qa/RawModeFidelityQaTest` | ✅ pass |
| Commit planning & text diff | `CommitPlannerTest`, `TextDiffTest`, `qa/CommitSeamQaTest` | ✅ pass |
| Clipboard privacy | `qa/ClipboardPrivacyQaTest` | ✅ pass |
| Typed-text safety | `qa/TypedTextSafetyQaTest` | ✅ pass |
| Dictation state machine | `DictationStateMachineTest`, `qa/StateMachineFuzzTest` | ✅ pass |
| Suggestions/autocorrect | `SuggestionEngineTest`, `LexiconTest`, `UserHistoryTest`, `qa/SuggestionEngineQaTest`, `qa/SuggestionFieldMatrixQaTest` | ✅ pass |
| Model catalog & installer | `ModelCatalogTest`, `PackInstallerTest`, `qa/ModelInstallerQaTest` | ✅ pass |
| Report-id regression pins | `qa/QaRegressionPinTest` (one test per VB-QA-01…12) | ✅ pass |

Run locally with `./gradlew -Pvboard.skipAndroid=true :core:test`; CI runs the
same suite plus the Android debug and R8 release builds on every push.

## 2. QA findings — first pass (VB-QA-01…12)

| ID | Severity | Finding | Status |
|---|---|---|---|
| VB-QA-10 | **Critical** | Model download failed at ~100% on device and every retry re-failed instantly: the installer treated the catalog's *estimated* `sizeBytes` as a hard minimum (`Files.size(part) < spec.sizeBytes` → `NETWORK`), and every real upstream artifact is smaller than the estimate (Zipformer 127,887,156 vs 130,000,000; Parakeet 482,468,385 vs 700,000,000). The complete `.part` then re-hashed instantly on retry (bar jumps to 100%), and the follow-up `Range:` request past the end of the file drew an HTTP 416, which `AndroidFetcher` reported as a network error — an unbreakable loop. Blocked first-run setup entirely. | **Fixed** — the installer asks the server for each file's authoritative length (`Fetcher.contentLength`, HEAD with a ranged-GET fallback) and gates on that; catalog sizes now only seed progress and the storage pre-check. A `.part` that already holds the whole file skips the request, an over-long remnant is discarded and redownloaded, and 416 is handled as "already complete". Real sizes pinned in the catalog. |
| VB-QA-11 | Medium | The storage pre-check reserved only the download size, but a `.tar.bz2` is extracted before it is deleted, so the peak footprint is ~2.5x. A device with just enough room passed the check and then failed during extraction. | **Fixed** — `ModelPack.installFootprintBytes` budgets 2.5x for archive files; the onboarding/settings error now names the space actually required. |
| VB-QA-01 | **High** | Repetition collapse corrupted spoken digit sequences: "five five five one two one two" → "five one two" (phone numbers destroyed; violates VB-203 "when uncertain, keep both"). | **Fixed** — number-like words (digits, number words) are exempt from word- and bigram-level collapse. Covered by re-enabled golden tests. |
| VB-QA-06 | **High** | Mixed-case tokens near a frequent word were autocorrected: "iPhone"→"phone", "iOS"→"is", "VBoard"→"Board" (risk R5: corrupting deliberate input). | **Fixed** — internal capitals now gate autocorrect exactly like ALL-CAPS. |
| VB-QA-07 | **High** | Two concurrent `install()` calls for the same pack interleaved writes into one `.part` file and could activate a corrupt model that passed its own running digest (breaks VB-403). | **Fixed** — per-pack `Mutex` serializes installs; second caller short-circuits on the marker. Concurrency test re-enabled. |
| VB-QA-09 | Medium | The typed word could vanish from the suggestion strip when three higher-scored candidates filled all slots (violates VB-306 "literal always reachable"). | **Fixed** — literal is forced into the left slot when ranking drops it. |
| VB-QA-02 | Medium | Interrogative utterances got a terminal "." instead of "?" (VB-204). | **Fixed** — utterances starting with an interrogative word (what/how/can/could/…) get "?"; known limit: interjection-led questions ("hey are you coming") still get ".". |
| VB-QA-03 | Low | "actually no" self-correction trigger from VB-202 was unimplemented. | **Fixed** — added as a strong marker with semantic alignment ("for may actually no june" → "for june"). |
| VB-QA-05 | Low | Idempotency (VB-206) breaks on pathological inputs: 5+ stacked correction markers, "scratch that scratch that" (output can re-trigger a command if re-cleaned), "\n\n\n" re-tokenizes to "\n\n". | **Open, documented** — cleanup runs exactly once per utterance in the product, so the double-clean path is unreachable in practice; test kept `@Disabled` as the spec-correct pin. |
| VB-QA-04 | Low | Raw mode is documented as bypassing every transformation, but the tokenizer runs first regardless. | **Superseded by VB-QA-17**, which turns the note into assertions. |
| VB-QA-12 | Medium | `$` was not in `Tokenizer.PUNCT_CHARS`, so "it costs $75 dollars" → "It costs 75 dollars." | ✅ **Fixed** by Package A, as a consequence of VB-QA-13 rather than in isolation. |

`VB-QA-08` was never assigned and is deliberately left unused.

## 3. QA findings — second pass (VB-QA-13…34)

Ids continue the series. Every finding is pinned by a **passing** test asserting
*current* behaviour, and where the correct behaviour is clear, by a `@Disabled`
test asserting what it *should* do.

**Status.** Wave 1.5 Package A has since landed, closing VB-QA-12, -13, -14,
-15, -16, -17, -21 and -27; Package C has since landed, closing VB-QA-22, -23,
-24, -25, -26, -28, -32, -33 and -34 — the whole of §3.3. Those rows are marked
✅ **Fixed** below and their tests are enabled. Everything else in this section
is still open. The findings are left describing the defect as it was found,
because that is what the enabled tests now assert the inverse of.

### 3.1 One defect, five symptoms

✅ **Fixed by Package A.** Described below as it was found.

`Tokens.kt` iterated UTF-16 `Char`s and kept a character only when
`Char.isLetterOrDigit()` was true or it appeared in a 13-character punctuation
allow-list. The `else` branch called `flushWord()`, so an unrecognized character
was not merely deleted — **it also inserted a word boundary, splitting the word
it sat inside**. That single mechanism produced VB-QA-13, -14, -15, -16 and -17.

What survived cleanup before the fix: anything `isLetterOrDigit()`; the 13 punctuation
characters `! " # % & ( ) , . : ; ? @`; `-` and `'` only when word-internal;
`…`/`...`; `\n`. Everything else was dropped — **88,833 of 138,552 named Unicode
code points, 64%**.

| ID | Severity | Finding | Evidence |
|---|---|---|---|
| VB-QA-15 | **Critical** · ✅ Fixed | Combining marks (Mn/Mc) are deleted *and* split their word. Devanagari, Thai and every script whose vowels/tone marks are combining characters become unwritable; for Latin the outcome depends on the input's normalization form, which no ASR engine guarantees. | `hindi नमस्ते दुनिया आज` → `Hindi नमस त द न य आज.` · NFD `café is open now` → `Cafe is open now.` |
| VB-QA-14 | **High** · ✅ Fixed | Every non-BMP code point is deleted — both halves of a surrogate pair fail `isLetterOrDigit()`. 77,702 named astral letters/numbers plus 6,605 BMP symbols. | `hello 👋 world here` → `Hello world here.` · `han 𠮷 char here` → `Han char here.` |
| VB-QA-13 | **High** · ✅ Fixed | Every symbol outside the allow-list is deleted and splits its word. By category: So 6,605 · Mn 1,950 · Sm 948 · Po 592 · Mc 445 · Cf 163 · Sc (currency) 63 · Pd 24 … | `the cost is €40` → `The cost is 40.` · `a + b = c` → `A b c.` · `C++ code here` → `C code here.` · `half is 1/2 cup` → `Half is 1 2 cup.` |
| VB-QA-17 | **High** · ✅ Fixed | Raw mode is not verbatim. `Cleanup.kt` documents it as bypassing every transformation except commands, but `TranscriptCleaner.clean` runs `Tokenizer.tokenize` **before** `rawMode` is consulted anywhere. This is the setting a user turns on *because* cleanup mangled something. | raw `hello 👋` → `hello` · raw `$75` → `75` · raw `under_score` → `under score` |
| VB-QA-16 | Medium · ✅ Fixed | Bidi controls (RLE/PDF/LRM/RLM) are stripped. Letters survive; the overrides that fixed their visual order do not, so mixed-direction text can render in a different order than dictated. | `‫هذا نص‬ عربي هنا` → order not preserved |

**This determined the shape of the fix.** It was not "add `$` to the allow-list".
A wider allow-list must enumerate 63 currency signs, 948 math symbols and 24
dashes and would still leave Mn/Mc/Cf and the whole astral plane broken. The
change that closed all five was a *policy* inversion: **iterate code points, and
go from allow-list-keep to deny-list-drop**, where the deny-list is only the
small closed set of ASR artifacts.

### 3.2 Destructive stages fire on surface form alone

| ID | Severity | Finding | Evidence |
|---|---|---|---|
| VB-QA-18 | **High** | Spoken punctuation is substituted whenever the preceding token is not a determiner; multi-word phrases (`full stop`, `question mark`, `new line`, `at sign`) have **no guard at all**. The symbol lands at position 0 and leading punctuation is then dropped, so the words vanish with nothing left to hint at why. **No `CleanupResult` counter reports this stage** (see G3), so the app cannot surface it either. | `full stop the car` → `The car` · `menstrual period tracking` → `Menstrual. Tracking` · `at sign up time` → `@Up time` |
| VB-QA-19 | **High** | "scratch that" mid-sentence takes the `isScratch` branch and cuts back to `clauseStartBefore`, which with no punctuation in the utterance is index 0. | `tell him i need to scratch that itch` → **`Itch`** (7 words in, 1 out) |
| VB-QA-20 | **High** | `no wait` / `wait no` fire as correction markers at index 0. Every other marker carries an `i > 0` requirement; these two do not. | `no wait for me` → `For me` · `no wait i am coming` → `I am coming.` |
| VB-QA-29 | Medium | `FieldKind.allowsAutoCapitalize` gates only the *first* word. `capitalize` then walks the whole token list, re-capitalizing after every `.`/`!`/`?` and every break — in EMAIL, URI, **PASSWORD** and NUMBER fields, which the spec says must be left alone. | PASSWORD: `hello. world here` → `hello. World here` |
| VB-QA-21 | Medium · ✅ Fixed | `ARTIFACT_REGEX` matches `\[[a-z_ ]+]`, so any bracketed lowercase prose is deleted — and artifact scrubbing is gated by **no option at all**, not even `rawMode`. | `see [see attached] for details` → `See for details.` |
| VB-QA-27 | Medium · ✅ Fixed | `sentenceStartsAt` knows only `.`, `!`, `?` and newline — no capitalization after a closing quote or bracket, after an ellipsis, or after `。`/`！`/`？`/`؟`/`।`. The quoted-sentence case is common in English messaging. | `abc." def` → `def` not capitalized |
| VB-QA-30 | Low | Adjacent `Tok.Break`s are never merged; the spoken path can emit three newlines. This is the mechanism behind case (c) of VB-QA-05. | `hello new paragraph new line world` |
| VB-QA-31 | Low | `"..."` is absent from `SENTENCE_ENDERS` and is not string-equal to `"."`, so punctuation stacks onto it. | `tell me comma ellipsis and go` → `Tell me,... and go.` |

### 3.3 Seams: classification by ASCII assumption

✅ **Fixed by Package C** — all nine. Described below as they were found.

| ID | Severity | Finding | Evidence |
|---|---|---|---|
| VB-QA-24 | **High** (privacy) · ✅ Fixed | `ClipClassifier`'s `OTP_PATTERN` and `DIGIT_RUN_PATTERN` use Java `\d` (ASCII-only) while `luhnValid` and `isNumberLike` use `Char.isDigit()` (Unicode-aware). The disagreement falls on the unsafe side: a one-time code or card number in non-ASCII digits is classified `NORMAL` and **written to the clipboard history file** instead of held `SESSION_ONLY`. | `١٢٣٤٥٦` (Arabic-Indic OTP) → persisted to disk |
| VB-QA-32 | **High** · ✅ Fixed | Accented Latin words are autocorrected into unrelated English words **in the shipping default mode**. `isCorrectableToken` accepts any `Char.isLetter()`; an out-of-lexicon literal scores only `LITERAL_PRIOR` while the lexicon word scores full log-frequency, clearing `CONSERVATIVE_MARGIN`. | CONSERVATIVE: `crème` → **`crime`**, `élan` → **`plan`**; strip: `Müller` → `[Miller, Müller, Killer]` |
| VB-QA-33 | Medium · ✅ Fixed | `ContentGuard.needsShield` treats a combining mark as unsafe content, so NFD `café` is shielded and escapes sentence casing while its NFC twin is capitalized. Typed cleanup is normalization-dependent. | NFC → `Café is open.`; NFD → `café is open.` |
| VB-QA-34 | Medium · ✅ Fixed | `needsShield` accepts a leading `-` as an ordinary word character, so CLI flags, markdown bullets and dashed asides pass unshielded into the tokenizer. `-5` *is* shielded (the digit trips the check), so the hole is specific to hyphen-then-letters. | `run git commit -m "fix" first` → `Run git commit - m "fix" first.` |
| VB-QA-22 | Medium · ✅ Fixed | `CommitPlanner.OPENERS`/`CLOSERS` are ASCII-only and incomplete — possessives detach, hyphenated compounds break, currency and closing curly quotes get a leading space. | `"hello" + "'s"` → `" 's"` |
| VB-QA-28 | Medium · ✅ Fixed | `TextDiff.replacement` correctly refuses to split a surrogate pair (verified over 4,000 randomized pairs) but the prefix can still land inside a grapheme cluster. Output text is correct; the intermediate composing region flickers through a broken glyph — exactly what the minimal diff exists to prevent. | `🇺🇸`→`🇺🇦` keeps 2 chars |
| VB-QA-23 | Low · ✅ Fixed | `doubleSpacePeriodApplies` inspects `precedingText[length-2]`, which after an emoji is a low surrogate and after NFD `café ` is a combining mark. Double-space-period silently stops working. | works after NFC `café `, not after NFD |
| VB-QA-25 | Low · ✅ Fixed | `ClipClassifier` trims with `String.trim()`, which removes whitespace but not format characters, so a clip of `U+200B` is stored and renders as an empty chip. | |
| VB-QA-26 | Low · ✅ Fixed | `luhnValid` guards with `Char.isDigit()` (Unicode-aware) then evaluates with `digits[i] - '0'` (ASCII). Unreachable today, but **becomes reachable the moment VB-QA-24 is fixed** — the two must be fixed together. | `luhnValid("١٢٣٤")` → `true` |

## 4. Gaps

**G1 — `EditorInfo` capitalization modes.** `FieldKind` collapses Android's
`CAP_MODE_SENTENCES` / `WORDS` / `CHARACTERS` / none into one boolean.
AnySoftKeyboard tests all four against the same input and expects four different
outputs; we cannot express `WORDS` (name, city fields) or `CHARACTERS` (postcode
fields) at all. This is a data-model gap in `core`, not an app-layer one.

**G2 — Nothing stops the next caller repeating the `ContentGuard` workaround.**
`CleanupRequest` has no field saying "this text was typed, not spoken", and
`CleanupOptions` has no flag that disables tokenizer-level destruction
(`rawMode` does not — VB-QA-17). The only protection is that `TypedTextCleanup`
happens to be the caller. The cost is quantified:
`check https://a.co/x now` → `Check https: a. Co x now.`, `3.14` → `3. 14`.
Likely next callers are an LLM refinement pass and a paste normalizer.
`TypedTextSafetyQaTest` asserts the absence reflectively and will fail loudly
when a guard field is added.

**G3 — Destructive stages are not disclosed.** `CleanupResult` counts fillers,
corrections and repetitions but has **no counter for spoken-command
substitution**, the stage responsible for VB-QA-18's worst cases. Any "undo
cleanup" affordance needs this signal.

**G4 — Catalog integrity is not uniform.** `qwen25-05b-refiner` still carries a
round estimate (`547_000_000`) and an empty `sha256`, while both ASR packs carry
measured sizes and real digests. §6's "sizes are now measured" should not be read
as covering every pack.

### Not yet thought of (product scope, not defects)

- **No recapitalization** — AOSP's `RecapitalizeStatus` cycles a selection through lower/Title/UPPER on repeated shift.
- **No autocorrect revert primitive in `core`** — the manual plan lists the behaviour but no primitive exists, and `TextDiff` is not it.
- **No spoken-number → digit conversion** — "five five five one two one two" is protected from collapse (VB-QA-01) but never becomes `5551212`.
- **No URL/email awareness in the *voice* path** — `ContentGuard` exists only for typed text.
- **No grapheme-cluster cursor arithmetic** — the spacebar-drag cursor has no `core` equivalent of HeliBoard's `moveStepsToCharCount`.
- **No `Locale`-aware casing** — `uppercaseChar()` is locale-independent; Turkish dotted/dotless `i` will be wrong.

## 5. What other keyboards test that we did not

Read: AnySoftKeyboard (`ime/app/src/test/java/com/anysoftkeyboard/`, ~55 methods
in `AnySoftKeyboardGimmicksTest` alone), HeliBoard
(`InputLogicTest` 70 methods, `StringUtilsTest`, `SuggestTest`), and the
AOSP-lineage `InputLogicTests`/`RecapitalizeStatusTests` they descend from.

| Their category | Applies to us because | Now covered by |
|---|---|---|
| Grapheme-cluster integrity (ZWJ flags, skin tones, keycaps, VS16, Zalgo) | `Tokenizer`, `TextDiff` and `CommitPlanner` all classify by UTF-16 `Char` | `UnicodeSafetyQaTest`, `CommitSeamQaTest` |
| Autospace / phantom-space rules | `CommitPlanner.joinForInsertion` is the same function with a shorter table | `CommitSeamQaTest` |
| Double-space-period edge cases | `doubleSpacePeriodApplies` was tested for ASCII only | `CommitSeamQaTest` |
| Deleting multi-code-point text | `TextDiff.replacement` is our equivalent seam | `CommitSeamQaTest` |
| `EditorInfo` cap-mode permutations | we have one boolean | partly — the missing modes are **G1** |
| Field-kind × mode matrix | our field gate is spread across three places | `SuggestionFieldMatrixQaTest` |
| Punctuation-word / symbol disambiguation | our spoken-punctuation stage has one guard | `SpokenCommandSafetyQaTest` |

Categories that need Robolectric and a live `InputConnection`, so they are not
`core`-testable: shift/caps-lock state machines, IME-action semantics,
long-press repeat timing, gesture disambiguation, recapitalization cycling,
autocorrect revert-on-backspace. Several are **also product gaps** — see §4.

### Honest assessment of the suite before this pass

1. **The 53-case golden corpus is a *ceiling*, not a floor.** It is entirely
   ASCII English. It tells you the pipeline is right about 53 English sentences;
   nothing told you it is catastrophically wrong about a Hindi one. 626 passing
   tests said nothing about 64% of Unicode.
2. **The property tests were narrower than they read.** `CleanupPropertyTest`
   fuzzes ~30 curated adversarial strings — enumeration wearing a property's
   clothes. Generating from a grammar of the tokens the pipeline actually
   branches on failed five structural invariants on the first run, two of them
   real bugs (VB-QA-29, VB-QA-30).
3. **Findings were pinned by behaviour, not by id.** You could not grep an id and
   get a yes/no. `QaRegressionPinTest` now gives one test per report id.
   Pins that asserted an *open* finding were deliberately written to fail when
   someone fixes it without updating this document; Package A tripped the
   VB-QA-12 pin, which was flipped to assert the fixed behaviour.

**The blind spot, stated plainly:** every destructive stage was tested only on
inputs where it was *supposed* to fire. Nothing tested the far larger space of
inputs where it must *not*. That asymmetry, not the test count, is why the suite
looked healthy.

## 6. Traceability highlights (core-testable requirements)

- VB-2xx cleanup requirements → `TranscriptCleanerTest`, `CleanupGoldenCorpusTest` (53 realistic utterances across messaging/email/notes/addresses/questions), `CleanupPropertyTest` (idempotency, never-throws, no double spaces, option independence, raw-mode contract).
- VB-1xx dictation lifecycle → `DictationStateMachineTest` (happy paths, continuous dictation, scratch-that, silence timeout, watchdog fallback semantics) + `StateMachineFuzzTest` invariants (no commit from Idle/Error, StopAudio on session exit, monotone utterance index, never throws).
- VB-3xx typing/autocorrect → `SuggestionEngineQaTest` (100 sampled lexicon words never autocorrected, casing gates, apostrophes, field gating: PASSWORD/NUMBER empty, EMAIL/URI literal-only, OFF-mode inertness).
- VB-4xx model management → `PackInstallerTest` + `ModelInstallerQaTest` (resume with exact byte accounting, checksum mismatch, cancellation, storage pre-check, process-restart persistence, version bump, concurrent installs, delete-during-download).
- Remaining VB-1xx/5xx UI, latency and privacy requirements are Android-layer: see manual plan below; latency NFRs need on-device measurement (Pixel 7-class).

## 7. Manual on-device test plan (top 20)

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

## 8. Known risks

- Endpoint tuning (0.8s trailing silence) needs real-device validation across speaking styles.
- Parakeet int8 cold-load takes seconds on first mic press; mitigated by the engine cache but worth a warm-up on IME bind.
- Catalog sha256 digests are still empty, so checksum verification is skipped — pin them before shipping. Sizes are now measured from the upstream assets, and the installer no longer depends on them being exact (VB-QA-10), but an unpinned hash means a corrupted-but-complete download would install.
- Size and 416 handling is verified against fakes; the live GitHub-release behaviour it models (200/206/416, `Content-Range` totals) was confirmed by hand and should be re-checked if the download host changes.
- MediaPipe LLM Inference is in maintenance mode upstream; migration to LiteRT-LM is a v2 consideration.

## 9. Work packages

Scheduled as **Wave 1.5** in [V2_PLAN.md §3](V2_PLAN.md) — A as W1.5.1, B as
W1.5.2, C as W1.5.3. The wave runs **before** Wave 1's feature work: bugs first,
features later. *Nothing is dispatched yet.* This section is written to be
read cold, weeks from now, by someone who was not present: it carries the file
ownership, constraints and definitions of done that the plan's table does not.

The three packages have no file overlap, so B and C are safe to run
concurrently. **A lands first** — it shrinks both of the others. W1.2 (spoken-format) also
touches `Tokens.kt` and must not start until A has landed — the wave ordering
already guarantees that.

### Package A — Unicode-safe text core ✅ **Landed**

**Why one package and not five.** VB-QA-13, -14, -15, -16 and -17 are one defect
described five ways (§3.1). Fixing any one symptom by widening the allow-list
makes the other four harder. The single change that closes all five: **iterate
code points, and invert the policy from allow-list-keep to deny-list-drop**,
where the deny-list is only the closed set of ASR artifacts. VB-QA-17 closes as a
consequence — raw mode stops losing content once the tokenizer stops deleting it.
VB-QA-21 is the same inversion applied to `ARTIFACT_REGEX`.

**Files:** `core/text/Tokens.kt` (owner), `TranscriptCleaner.kt`
(`ARTIFACT_REGEX` and the `scrubArtifacts` call site only), `Cleanup.kt`.
**Tests to re-enable:** `UnicodeSafetyQaTest`, `TokenizerSymbolLossQaTest`,
`RawModeFidelityQaTest`, plus `CleanupGoldenCorpusTest`'s VB-QA-12 skip.

**Done when:** 11 `@Disabled` tests pass with the annotation removed; the golden
corpus's 53 cases are unchanged; `CleanupInvariantQaTest` is still green — it is
the guard against a fix that preserves *too much* and breaks output hygiene.

**Outcome.** All 11 are enabled and green; the core suite is 761 tests, 0
failures, 18 skipped (was 29). The golden corpus needed one case changed —
`that jacket costs $75` now keeps its `$`, which is the VB-QA-12 fix rather than
a corpus edit — and `QaRegressionPinTest`'s VB-QA-12 pin was flipped for the same
reason. `sentenceStartsAt` was taken by A after all, closing VB-QA-27; Package B
inherits it in its fixed state. Two additions beyond the plan: the non-raw path
normalizes to NFC (raw mode is exempt, since normalization is itself a
transformation), and structural punctuation with a word character on both sides
is treated as intra-word, so `a_b@c.com` and `well-known` survive as one word.

**Land this first.** Package B's `sentenceStartsAt` work and Package C's
`ContentGuard` work both shrink or disappear once it does: VB-QA-33 and -34 exist
only because `ContentGuard` is compensating for `Tokenizer`. Only half of that
was borne out, and Package C settled it: `TypedTextSafetyQaTest`'s
guarded/unguarded comparison pairs are indeed now identical, so the *shield* no
longer changes the outcome for those inputs — but VB-QA-33 and -34 are defects in
`ContentGuard.needsShield` itself, both `@Disabled` tests still genuinely failed
with the annotation removed, and both needed real `ContentGuard` changes. Package
C made them, and `ContentGuard.kt` was therefore Package C's fourth file.

### Package B — Destructive-stage confidence and field-kind honesty

**Why one package.** VB-QA-18, -19 and -20 are three instances of "a stage
deletes words on surface-form evidence alone", living in the same two functions
(`matchSpokenPhrase`, `findMarker`/`resolveSelfCorrections`); any confidence
model has to serve both. VB-QA-29 and G3 join them because they answer the same
question — *what is this stage allowed to do, and does it tell anyone it did it?*

**Constraints a fixer must respect** (all pinned by passing tests, so violations
will be caught): `call me at five no wait six` → `Call me at six.` must keep
working; the determiner guard's six current saves must keep working;
`scratch that` / `stop listening` as whole utterances must stay commands;
`resolveSelfCorrections = false` and `spokenCommands = false` must remain fully
inert.

**Files:** `core/text/TranscriptCleaner.kt` (owner — stages 2, 3, 5, and
`capitalize`), `FieldKind.kt`, `Cleanup.kt` (`CleanupResult`, for the G3
counter). **Tests:** `SpokenCommandSafetyQaTest`, `CleanupInvariantQaTest`.

*The `sentenceStartsAt` overlap with Package A was the one place the boundary was
not clean, and A took it* — VB-QA-27's test lives in `UnicodeSafetyQaTest`, which
A owned. B inherits `sentenceStartsAt` already fixed and should not need to touch
it; the `capitalize` walk it does still own is VB-QA-29.

**Done when:** its 7 remaining `@Disabled` tests pass; `CleanupResult` gains a counter for
spoken-command substitutions and the counter-honesty test is rewritten to assert
the new behaviour; VB-QA-29's `PASSWORD` case is closed — it is the one with a
privacy story.

### Package C — Seams: commit planning, clipboard privacy, suggestion ranking ✅ **Landed**

**Why these travel together.** They are the places a *classification*
decision is made about a single character or token, and all are wrong the
same way — they classify by ASCII assumption. None touches `TranscriptCleaner.kt`
or `Tokens.kt`, so the package is fully disjoint from A and B.

**Files:** `core/text/CommitPlanner.kt` (VB-QA-22, -23, -28),
`core/clipboard/ClipClassifier.kt` (VB-QA-24, -25, -26),
`core/suggest/SuggestionEngine.kt` (VB-QA-32), `core/correct/ContentGuard.kt`
(VB-QA-33, -34) — **four production files, not the three earlier drafts of this
section listed.** VB-QA-33 and -34 are defects in `needsShield` itself, so
`ContentGuard.kt` came with them. **Tests:** `CommitSeamQaTest`,
`ClipboardPrivacyQaTest`, `SuggestionFieldMatrixQaTest`, `TypedTextSafetyQaTest`.

**Two hard constraints.** (1) VB-QA-24 and VB-QA-26 **must be fixed in the same
change** — `luhnValid`'s ASCII arithmetic is unreachable only because
`DIGIT_RUN_PATTERN` is ASCII-only, so widening the pattern alone converts a false
negative into a false positive. (2) VB-QA-32's fix must not weaken VB-QA-06 or
VB-QA-09; `QaRegressionPinTest` asserts both and
`SuggestionFieldMatrixQaTest`'s 18,000-case strip invariants are the backstop.

**Done when:** 10 `@Disabled` tests pass (the two VB-QA-33/-34 tests in
`TypedTextSafetyQaTest` belong to this count) and the 500-step clipboard
retention fuzz is still green — it is what would catch a fix that classifies too
aggressively and starts dropping ordinary clips.

**Outcome.** All 10 are enabled and green; the core suite is 762 tests, 0
failures, 8 skipped (was 761 / 18). The +1 test is one new regression test; the
remaining 8 skips are all outside this package —
`SpokenCommandSafetyQaTest` (4), `CleanupInvariantQaTest` (3) and
`CleanupPropertyTest` (1, VB-QA-05). Nothing overshot:
`CleanupGoldenCorpusTest` (58 tests) is untouched with no golden case changed,
`CleanupInvariantQaTest` is 21/0, `QaRegressionPinTest` is 12/0 with its VB-QA-06
and VB-QA-09 pins intact, and the clipboard retention fuzz,
`SuggestionFieldMatrixQaTest`'s 18,000-case strip invariants,
`CommitSeamQaTest`'s 4,000-pair diff reconstruction fuzz and
`TypedTextSafetyQaTest`'s 4,000-case content-loss fuzz are all green. Eight files
changed; `QaRegressionPinTest.kt`, `ClipClassifierTest.kt`, `CommitPlannerTest.kt`,
`ContentPreservationTest.kt` and `ClipboardHistory.kt` were not touched.

Five things a later reader will want to know:

- **Ten regression pins were flipped, not deleted** — 3 in `CommitSeamQaTest`,
  3 in `ClipboardPrivacyQaTest`, 2 in `SuggestionFieldMatrixQaTest`, 2 in
  `TypedTextSafetyQaTest`. Each had asserted the *unfixed* behaviour next to its
  disabled twin; each was inverted to assert the fix and renamed. Same precedent
  as Package A's VB-QA-12 pin. `QaRegressionPinTest` holds no pin for any Package
  C id (its ids stop at 12) and was not touched.
- **One extra defect was found and closed beyond the nine ids, and it carries no
  VB-QA id.** A privacy audit caught that the fixed OTP rule still trimmed with
  `String.trim()`, which does not strip Cf format characters, so a one-time code
  carrying a zero-width space or a BOM (and its Arabic-Indic equivalent) was
  classified `NORMAL` and persisted to disk — the same `SESSION_ONLY` crossing as
  VB-QA-24, one layer out. The blank predicate and the OTP predicate now share a
  single private `isInvisible` helper so they cannot drift apart again, and
  `ClipboardPrivacyQaTest.a one-time code wearing an invisible character is still
  session-only` pins it.
- **`java.text.BreakIterator` was tried and rejected for VB-QA-28.** On the
  pinned JDK 17 toolchain its character instance implements legacy clusters and
  splits ZWJ sequences, regional-indicator flag pairs and emoji skin-tone
  modifiers — the exact inputs the fix exists for. `TextDiff.replacement`'s
  round-down to a grapheme-cluster boundary is hand-rolled for that reason.
- **VB-QA-32 was closed without touching `LITERAL_PRIOR` or `Lexicon`.** The
  natural lever — charging a non-ASCII↔ASCII substitution a blocking edit cost —
  lives in `Lexicon.fuzzyDescend`, which this package did not own. One predicate
  in `SuggestionEngine` (the token contains a letter outside `a-z`) skips fuzzy
  matching and gates autocorrect instead, so ASCII ranking is unmoved and the
  VB-QA-06/-09 constraint holds by construction.
- **Two limitations knowingly left open.** `DIGIT_RUN_PATTERN`'s separator class
  is still ASCII-only (`[ -]?`), so a card number grouped with NBSP, U+2007 or an
  en-dash still evades the Luhn check and is persisted — deliberately deferred.
  And `ContentGuard.Shield` is a plain class with a hand-written count-only
  `toString`, deliberately not a `data class`, so shielded user content cannot
  reach a log; adding `data` or an IDE-generated `toString` would silently defeat
  that and no test would catch it.

### Not in any package

VB-QA-30 and VB-QA-31 are cosmetic and belong to whoever next touches
`normalizePunctuationSequence`; both are pinned, neither is worth a dedicated
change. **G1** (`EditorInfo` cap modes) and **G2** (a typed-text guard in the
cleanup API) are design decisions rather than bug fixes and should be decided
before either is scheduled — G2 in particular becomes much smaller after Package
A. The "not yet thought of" items in §4 are product scope, listed here so they
are not rediscovered as bugs later.
