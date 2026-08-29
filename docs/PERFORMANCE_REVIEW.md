# VBoard Performance Review

Status: **findings captured for review. Nothing here is being built.**
Date: 2026-08-29 · Source: Android performance review, verified against the source.

Companion documents: [V2_PLAN.md](V2_PLAN.md) · [V2_PROPOSALS.md](V2_PROPOSALS.md)

---

## 0. The honest starting point

**Every budget in the product spec is a claim nobody has measured.** Cold open ~150ms,
tap-to-glyph ~40ms, first partial word <300ms, ≤60MB typing / ≤400MB dictating, APK <30MB.
There is no instrumentation in the app at all.

That includes the estimates in this document. Where a number is arithmetic or inference
rather than measurement, it is marked **INFERRED** and the measurement that would settle it
is named.

---

## 1. Every keystroke blocks on the target app's main thread

**Verified.** `precedingText()` is a synchronous `InputConnection.getTextBeforeCursor` — a
blocking cross-process round trip that returns only when the *foreground app's* main
thread services it.

Callers on the typing path: `onSpace` (double-space-period check), `refreshSuggestions`,
`learn`, `updateShiftForContext`, and `previousCommittedWord`. A single space press
executes **four blocking round trips and three unbatched edits**. `learn()` and
`refreshSuggestions()` each call `previousCommittedWord()` independently with nothing
changed between them — a duplicated round trip on every keystroke.

**`beginBatchEdit` appears exactly once in the entire IME service.** Unbatched, each
`commitText`/`setComposingText` makes the peer relayout, redraw, and fire
`onUpdateSelection` back at us.

**Why it matters:** the 40ms tap-latency budget is partly controlled by a process we do not
own. Fine on an idle field; bad in a scrolling chat list or a WebView-backed input. This is
also the most likely ANR source in the codebase.

**Fix.** Maintain a local shadow of the last ~64 characters before the cursor — the IME
already knows every character it commits, and `onUpdateSelection` already detects
unexpected moves, so it only needs to resync there and on `onStartInput`. Seed it with
**zero IPC** using `EditorInfo.getInitialTextBeforeCursor`, which arrives with the
EditorInfo already fetched (API 30+, one branch for minSdk 29). Deduplicate the shared
call, and wrap multi-call edits in `begin`/`endBatchEdit`.

**Effort** ~2–3 days. **Verify** with trace sections around DOWN→`commitText`, including a
deliberately janky host whose main thread sleeps 30ms per frame.

---

## 2. The microphone will not open until the accuracy model has loaded

**Verified.** `VoiceEngines.load` constructs *both* recognizers before returning READY, and
audio capture only starts after that. Nothing needs the accuracy model until the first
endpoint, seconds later.

**Symptom.** The first words of every cold dictation are not captured. With the 90-second
idle release, this recurs several times a day.

**Fix.** Split loading so the streaming recognizer alone gates the microphone and the
accuracy model loads concurrently; start capture in parallel with both. The audio pipeline
already separates the utterance buffer from the decode queue and already tolerates a
decoder that is absent or behind, so the reader can buffer from t=0 and the partial catches
up. Also cache the model-path lookup — it currently walks the extracted directory twice per
cold load and filters the result six more times.

**Effort** ~1–2 days for the bulk of the win.

---

## 3. No Baseline Profile

`androidx.profileinstaller` is absent. For a process Android creates and kills many times a
day, this is the highest-leverage single build-config change available: without a profile,
every method on the startup and first-typing path runs interpreted until JIT warms it, and
the JIT competes for CPU during exactly the window we are trying to shrink.

This codebase has an unusually large amount of cold Java on that path — a 50,000-word
lexicon parse, key-bounds computation, the whole draw switch, the fuzzy-match walk, the
tokenizer.

**It pays twice:** the same macrobenchmark module that generates the profile also provides
the measurement harness this project has never had.

**Also worth doing to R8:** the sherpa keep rule is far broader than needed
(`-keep class ... { *; }` where only JNI entry points need keeping); add
`-allowaccessmodification` and `-repackageclasses`; add a CI gate on APK size against the
30MB budget; and note `material-icons-extended` is pulled in for twelve icons.

---

## 4. WorkManager initialises inside the keyboard process at every cold start

**INFERRED magnitude, verified mechanism.** `work-runtime-ktx` ships an auto-init
ContentProvider. With no `android:process` on anything, it runs in the same process that
hosts the IME — so every keyboard process creation opens a SQLite database and builds
schedulers, before the first frame, for functionality the keyboard never uses.

**Fix.** On-demand init (remove the startup provider, implement `Configuration.Provider`),
and better, move the activities and the downloader to `android:process=":ui"` — see §7.

---

## 5. The lexicon is ~20MB of Java heap, parsed at every process start

**Counted, not estimated:** the 50,009-word list produces **120,261 trie nodes**, 84,625 of
them with children — each a separate object holding a nullable `HashMap` — plus a second
50,000-entry map with boxed values. **INFERRED** retained size ~18–22MB, which is roughly a
third of the entire 60MB typing-only budget. The parse also creates ~250,000 short-lived
strings, during the cold-open window.

**Fix.** Compile it at build time into a flat binary and `mmap` it. Heap cost drops to a
handful of objects; the mapped pages are clean, file-backed and **evictable by the kernel
under pressure** rather than OOM-kill fodder — which is the whole point of thinking about
memory shape rather than totals. Startup cost drops from a 50,000-line parse to an mmap,
so suggestions are live from the first keystroke. APK impact is neutral or better.

Existing lexicon and suggestion tests pin the behaviour, so the port is verifiable.

---

## 6. The clipboard is read synchronously on the main thread at every keyboard show

**Verified.** `onStartInputView` reads the system clipboard before the first frame — a
binder call to system_server, and for a URI-backed clip, `coerceToText` **opens the source
app's ContentProvider**. The code already catches hostile-provider exceptions, so the
hazard is known; it is simply on the cold-open path.

**Fix.** Post it after the first frame, or read on a background thread and hand back only
the resulting string. The chip has a 60-second window; it does not need to exist in frame 0.

---

## 7. Other findings

- **First frame renders with default settings, then re-lays out.** DataStore's first read is
  async, so the keyboard's first frame uses hard-coded defaults for theme and number-row and
  swaps when the real values land — a visible flash on every cold open for anyone not on the
  defaults. Fix: mirror the snapshot into SharedPreferences and seed synchronously.
- **`onStartInputView` re-themes and re-lays out unconditionally** on every field focus,
  even when nothing changed. `applySettings` gets this right; this path does not. Because
  the layout setter clears key bounds, `computeKeyBounds` then runs *inside* `onDraw`,
  allocating inside a frame.
- **A single pressed key repaints the whole keyboard, twice per tap.** Full-view
  `invalidate()` on DOWN and UP, plus on every accessibility hover move.
- **The microphone reader runs on `Dispatchers.IO` at default priority** — the one thread
  that must never fall behind, sharing a pool with DataStore writes, clipboard saves, and
  model extraction (which decompresses a multi-hundred-megabyte archive). Its falling behind
  is the exact defect the audio pipeline was built to detect. Fix: dedicated thread at
  `THREAD_PRIORITY_URGENT_AUDIO`.
- **The IME process also hosts Compose, settings, onboarding and the downloader.** Once a
  user opens settings, the Compose runtime is class-loaded into the keyboard process for its
  lifetime. Settings composition also calls `PackInstaller.stateOf`, which does disk I/O
  **inside a composition**, re-run on every recomposition.
- **`onTrimMemory` releases native models but nothing Java**, and `UI_HIDDEN` — the most
  frequent signal an IME receives — only drops the refiner. Panels and popups are retained
  for the life of the service after one use.
- **Suggestion work is uncancellable**: the cancel call is a no-op because the search has no
  suspension point, so every keystroke's search runs to completion on one thread even when
  its result is discarded. Plus several hundred wasted string allocations per keystroke from
  redundant normalization.
- **The suggestion strip measures text and creates typefaces every frame**, and its
  ellipsize loop drops one character at a time with a fresh measurement and allocation per
  iteration — inside a frame.
- **Haptic feedback on ACTION_DOWN may be a blocking binder call** on API 29–33
  (**INFERRED**; a Perfetto trace with the binder data source would settle it).

---

## 8. Instrumentation to add first

None of this touches user content, and that constraint is absolute.

1. **Six trace sections mapping 1:1 to the spec's budgets** — cold open, tap-to-glyph, mic
   tap to listening, speech onset to first partial, finalize to commit, with cleanup nested
   inside. Static literal names only.
2. **Counters, not words** — memory stats, native heap, dropped audio samples (already
   accounted internally, just never surfaced). Counters are integers; content cannot leak
   into one.
3. **Frame metrics** aggregated into fixed histograms with fixed state names.
4. **A macrobenchmark module** driving a host activity, which also generates the Baseline
   Profile — one piece of infrastructure, two payoffs. Treat CI numbers as regression
   detection only; budget verification stays on reference hardware.
5. **StrictMode in debug builds** — would have caught the clipboard read and the composition
   disk I/O on the first run, for free.
6. **An APK size gate in CI** against the 30MB budget.

**Review-checklist rule:** trace section and counter names must be compile-time constants.
No name derived from typed or spoken text, and no length-of-transcript metric that could act
as a side channel.

---

## 9. Ranked

1. Kill the per-keystroke blocking IPCs with a local shadow buffer.
2. Unblock the microphone from accuracy-model loading.
3. Baseline Profile plus the macrobenchmark module that generates it.
4. Replace the parsed text lexicon with a prebuilt, memory-mapped structure.
5. Move non-keyboard code out of the keyboard process.

**But the first thing is the harness, not any of these.** Every number above — including
the ones in this document — is a claim until the six spans are on a timeline. It is also the
cheapest item that pays twice, since the module that measures cold start produces the
profile that improves it.
