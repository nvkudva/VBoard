# VBoard Design Specification v1.0

Voice-first Android IME. English-only v1. On-device, private. This document is the
single source of truth for visual design, layout, motion, and copy. All values are
final; engineering should map tokens (`vb.*`) 1:1 to code constants.

---

## 1. Design Principles & Brand Identity

### 1.1 Principles (in priority order)

1. **Voice is the hero.** The mic is the most prominent interactive element on every
   surface. Voice states get the richest motion; typing is the quiet fallback.
2. **Private by design, visibly.** On-device processing is a headline feature — the UI
   says it (copy, iconography) but never nags.
3. **Calm speed.** Every interaction responds in ≤ 1 frame; animations never block
   input. Nothing bounces or overshoots except the mic orb.
4. **One glance, zero reading.** Keys, states, and errors are legible at arm's length.
   All text meets WCAG AA (≥ 4.5:1); key labels exceed 7:1.
5. **Material You aware, not Material You dressed.** We respect dynamic-color wallpaper
   extraction only for the accent when the user opts in; the default identity is ours.

### 1.2 Brand

- **Accent name:** **Resonance Teal** — a saturated teal that reads "signal / live audio"
  without borrowing Google blue or recorder red.
- **Voice identity:** the **orb** — a filled circle that breathes with amplitude.

### 1.3 Color palette (exact hex, both themes)

| Token | Light | Dark | Use |
|---|---|---|---|
| `vb.color.bgKeyboard` | `#ECEEF1` | `#131417` | Keyboard background plane |
| `vb.color.keySurface` | `#FFFFFF` | `#24262B` | Letter keys |
| `vb.color.keySurfaceAlt` | `#D9DDE3` | `#33363D` | Function keys (shift, del, ?123, enter-idle, symbols) |
| `vb.color.keyText` | `#1B1C1E` | `#E8EAED` | Key labels |
| `vb.color.keyTextSecondary` | `#5F6368` | `#9AA0A6` | Hint chars (top-right of key), spacebar label |
| `vb.color.accent` | `#007A70` | `#35D0C2` | Mic key fill, action enter, links, toggles |
| `vb.color.onAccent` | `#FFFFFF` | `#00332E` | Icons/text on accent |
| `vb.color.suggestionBg` | `#F4F6F8` | `#1B1D21` | Suggestion strip background |
| `vb.color.suggestionText` | `#3C4043` | `#DADCE0` | Suggestion candidates |
| `vb.color.suggestionAutocorrect` | `#007A70` | `#35D0C2` | Center-slot autocorrect text (bold) |
| `vb.color.keyPressed` | `#C7CCD4` | `#3F434B` | Pressed overlay fill (letter keys) |
| `vb.color.keyPressedAlt` | `#B4BAC4` | `#4A4F58` | Pressed overlay fill (function keys) |
| `vb.color.error` | `#B3261E` | `#F2B8B5` | Errors, destructive actions |
| `vb.color.onError` | `#FFFFFF` | `#601410` | Text/icons on error fill |
| `vb.color.micPulse` | `#00A894` | `#35D0C2` | Orb amplitude halo (rendered at 24 % alpha) |
| `vb.color.transcriptPartial` | `#8A9095` | `#7C828A` | Partial (unfinalized) voice words |
| `vb.color.transcriptFinal` | `#1B1C1E` | `#E8EAED` | Finalized voice words |
| `vb.color.popupSurface` | `#FFFFFF` | `#2E3138` | Key preview & long-press popups |
| `vb.color.scrim` | `#000000` 32 % | `#000000` 48 % | Behind long-press popups only if popup overlaps keys |

Material You: when `Settings → Theme → Dynamic color` is ON, only `vb.color.accent`,
`vb.color.onAccent`, `vb.color.micPulse`, `vb.color.suggestionAutocorrect` remap to the
system tonal palette (accent = tone 40 light / tone 80 dark). All neutrals stay VBoard's.

### 1.4 Elevation, shape, depth

- Keys are **flat fills, no shadows** (performance + calm). Depth is communicated by the
  pressed overlay and the key preview popup only.
- Popups (key preview, long-press): 8dp elevation shadow (`0 2dp 8dp #000000` 26 % light,
  40 % dark).
- Corner radii: keys `vb.shape.keyRadius = 8dp`; spacebar & voice bar `12dp`; popups
  `12dp`; orb is a circle; suggestion chips `16dp`; onboarding cards `24dp` (M3 large).

### 1.5 Typography

Font: **Google Sans** if present on device, else **Roboto**. Weights: Regular 400,
Medium 500. No custom font shipped.

| Token | Size / weight | Use |
|---|---|---|
| `vb.type.keyLabel` | 22sp / 400 | Letter key labels (lowercase glyphs shown when unshifted) |
| `vb.type.keyLabelSmall` | 16sp / 400 | Symbol layer labels, hint characters at 11sp |
| `vb.type.spacebarLabel` | 13sp / 500 | "VBoard · EN" on spacebar |
| `vb.type.suggestion` | 16sp / 400 (center slot 500) | Suggestion strip |
| `vb.type.voiceTranscript` | 18sp / 400, line height 24dp | Voice bar live text |
| `vb.type.voiceHint` | 14sp / 400 | "Listening…", error captions |
| `vb.type.popup` | 26sp / 400 | Key preview character |

---

## 2. Keyboard Metrics

All values dp. Baseline device: 411dp-wide portrait phone.

- **Row height** `vb.dim.rowHeight = clamp(screenHeightDp × 0.088, 48, 58)` → default **54dp**.
- **Keyboard body height (portrait)** = suggestion strip 44dp + 4 rows × 54dp +
  3 inner vertical gaps × 8dp + top padding 6dp + bottom padding 8dp = **298dp** before
  system insets. Landscape: rowHeight fixed 44dp → body 258dp.
- **Key gaps:** horizontal `vb.dim.keyGapH = 5dp`, vertical `vb.dim.keyGapV = 8dp`
  (visual gap; see touch note below).
- **Side padding:** `vb.dim.sidePadding = 4dp` each edge.
- **Key width (letter rows):** `(kbWidth − 2×4 − 9×5) / 10` → ≈ 35.8dp visual on 411dp.
- **Touch targets:** every key's touchable region extends into half of each adjacent gap
  and 4dp above/below its visual bounds → all keys ≥ 48×48dp touchable even though
  visuals are smaller. Never reject a touch that lands in a gap; assign to nearest key.
- **Suggestion strip height** `vb.dim.stripHeight = 44dp`.
- **Key preview popup:** 56dp wide × 68dp tall, anchored centered above the key, bottom
  edge 4dp above the key top. Shows the committed character at 26sp.
- **Long-press popup:** row of candidate cells, each 48×54dp, radius 12dp container,
  max 7 cells per row then wraps to a second row; opens above the key, grows toward
  screen center horizontally; first candidate pre-highlighted with `vb.color.keyPressed`.
  Slide finger to select, release to commit; release outside cancels.
- **Bottom inset (gesture nav):** keyboard adds `max(systemGestureInsetBottom, 8dp)` of
  padding below the last row, painted `vb.color.bgKeyboard`. In 3-button nav mode add 0
  extra (system draws its own bar). Voice bar uses the same rule.

---

## 3. Layouts

### 3.1 QWERTY (base layer)

```
Row 1: q  w  e  r  t  y  u  i  o  p          (hint digits 1-0 top-right, 11sp)
Row 2:   a  s  d  f  g  h  j  k  l           (indented ½ key each side)
Row 3: ⇧  z  x  c  v  b  n  m  ⌫             (⇧ and ⌫ are 1.5× key width)
Row 4: ?123 | , | 🎤 | [space] | . | ⏎
```

**Bottom row (final):** widths on a 10-unit grid — `?123` 1.5u, comma 1u, **mic 1.25u**,
spacebar 4u, period 1u, enter 1.25u.

- **Mic placement decision: a dedicated key LEFT of the spacebar**, filled with
  `vb.color.accent`, mic glyph in `vb.color.onAccent`. It is the only always-colored key
  on the board — the visual hero. (Not on the suggestion strip: the strip disappears
  during voice and is hidden in password fields; a bottom-row key is always reachable by
  thumb.) Tap → voice mode (§4). Long-press → voice settings sheet.
- **Spacebar:** label "VBoard · EN" in `vb.type.spacebarLabel`, `keyTextSecondary`.
  Long-press: nothing in v1 (English-only); cursor-drag on horizontal slide ≥ 12dp.
- **Enter:** function key surface; swaps to accent fill + `onAccent` icon when the
  editor action is send/search/go/done. Icons: return ↵, send ➤, search 🔍, done ✓.
- **Period long-press popup:** `. , ? ! ' " : ;` — comma **long-press: emoji panel**
  (smiley icon shown as 11sp hint on the comma key).
- **?123 long-press:** clipboard (v1.1 placeholder — no-op in v1, no hint shown).

### 3.2 Symbols layer 1 (`?123`)

```
Row 1: 1 2 3 4 5 6 7 8 9 0
Row 2: @ # $ _ & - + ( ) /
Row 3: =\< | * " ' : ; ! ? | ⌫        (=\< switches to layer 2, 1.5u; ⌫ 1.5u)
Row 4: ABC | 😊 | 🎤 | [space] | . | ⏎
```

`ABC` returns to base. Emoji key (1u) replaces comma here. Mic stays in place — voice is
reachable from every layer.

### 3.3 Symbols layer 2 (`=\<`)

```
Row 1: ~ ` | • √ π ÷ × ¶ ∆
Row 2: £ € ¥ ^ ° = { } \
Row 3: ?123 | % © ® ™ ✓ [ ] | ⌫
Row 4: ABC | < | 🎤 | [space] | > | ⏎
```

### 3.4 Long-press popup contents (base layer)

- Vowels/consonants with accents: `a → à á â ä æ ã å ā`, `e → è é ê ë ē ė ę`,
  `i → î ï í ī į ì`, `o → ô ö ò ó œ ø ō õ`, `u → û ü ù ú ū`, `n → ñ ń`, `c → ç ć č`,
  `s → ß ś š`, `y → ÿ`, `z → ž ź ż`.
- Digit hints on row 1: long-press commits the digit (popup shows digit only).
- No popups on ⇧, ⌫, space, enter.

### 3.5 Emoji panel

Replaces key rows (strip stays, showing recently used emoji as chips). Height = keyboard
body height. Layout: 8-column grid, 40×40dp cells, emoji rendered at 28sp.
Bottom bar (44dp): `ABC` return key left, category tabs center — 🕘 Recents, 😀 Smileys,
🐻 Animals & Nature, 🍔 Food & Drink, ⚽ Activities, ✈️ Travel, 💡 Objects, ❤️ Symbols,
🏁 Flags — ⌫ right. Active tab: 2dp accent underline. Horizontal paging swipe between
categories; sticky category header row (28dp, 12sp `keyTextSecondary`). Long-press on
emoji with variants opens skin-tone popup (same popup spec as keys).

---

## 4. The Voice Bar (hero surface)

### 4.1 Geometry & layout

Total height **`vb.dim.voiceBarHeight = 120dp`** + bottom inset (portrait & landscape).
Background `vb.color.bgKeyboard`, top corners 12dp radius, hairline top divider
(`#000000` 8 % light / `#FFFFFF` 8 % dark).

- **Transcript zone** (top, 64dp): full width minus 16dp side padding. Shows the last
  **2 lines** of `vb.type.voiceTranscript`, bottom-aligned, older text scrolls up and
  fades (12dp fade gradient at top). Partial words render in
  `vb.color.transcriptPartial`; on finalization each word recolors to
  `vb.color.transcriptFinal` with a 120ms color crossfade, staggered 20ms per word.
  Empty state: "Listening…" in `vb.type.voiceHint`, `transcriptPartial` color.
- **Control row** (bottom, 56dp): three items —
  - Left: **keyboard-return button**, 48×48dp touch, keyboard glyph 24dp,
    `keyTextSecondary`. Tap → back to QWERTY.
  - Center: **the orb**, 56dp circle, `vb.color.accent` fill, 24dp mic glyph in
    `onAccent`. Amplitude halo behind it: a circle scaling 56→88dp with input RMS,
    `vb.color.micPulse` at 24 % alpha. Tap orb while listening → stop & finalize.
  - Right: **overflow button** (⋯), 48×48dp, opens sheet: Auto-punctuation toggle,
    Raw transcript toggle, Voice settings. (English-only v1: no language switcher.)

### 4.2 States & transitions

| # | State | Visual | Enter transition |
|---|---|---|---|
| V0 | idle (keyboard) | — | — |
| V1 | connecting | orb at 40 % alpha, halo static; hint "Starting mic…" | Keyboard→bar collapse: keyboard translates down & fades out while voice bar slides up; **250ms**, `EmphasizedDecelerate (0.05, 0.7, 0.1, 1.0)`. Crossfade content at 150ms. |
| V2 | listening | orb full alpha; **idle breath**: scale 1.00→1.06→1.00, 1200ms loop, sine easing; **amplitude halo**: radius = 28dp + 16dp × smoothedRMS (attack 50ms, release 300ms), alpha 24 % × RMS | from V1: 150ms fade |
| V3 | endpointing / finalizing | last utterance's partial words show a left→right **shimmer** (white 12 % overlay sweep, 800ms loop); orb breath continues, halo frozen at 0 | auto on VAD endpoint |
| V4 | cleaning ✨ | tiny "✨ Cleaning up…" chip (24dp tall, `suggestionBg` fill, 12sp) fades in above transcript right edge; shimmer continues | only if LLM refinement ON and pass > 150ms; chip fades in 100ms |
| V5 | committed | finalized text recolor (see §4.1); chip fades out 100ms | — |
| V6 | error | orb fill → `vb.color.error`, glyph `onError`; halo off; hint line shows message + inline action | 150ms color crossfade |
| V7 | return to keyboard | reverse of V1 collapse: **200ms**, `EmphasizedAccelerate (0.3, 0.0, 0.8, 0.15)` | user taps keyboard-return, or field loses focus |

Auto-timeout: after 8s of silence in V2, finalize and return to keyboard automatically.

**Error messages (exact copy, `vb.type.voiceHint`):**
- No mic permission: "Microphone access is off. **Allow** " (Allow → system settings).
- Model missing: "Voice model not downloaded. **Download (≈1 GB)**" (→ model manager).
- Mic busy: "Microphone is in use by another app. **Retry**".
- Recognizer crash: "Something went wrong. **Try again**".

### 4.3 Haptics (voice)

| Event | Constant |
|---|---|
| Mic key tap (enter voice) | `KEYBOARD_TAP` |
| Listening actually started (V2) | `CONFIRM` |
| Utterance finalized (V5) | `CONTEXT_CLICK` |
| Error entered (V6) | `REJECT` |
| Return to keyboard | `KEYBOARD_TAP` |

---

## 5. Key States

| State | Letter key | Function key | Mic key |
|---|---|---|---|
| Normal | `keySurface` fill, `keyText` label | `keySurfaceAlt` fill | `accent` fill, `onAccent` glyph |
| Pressed | fill → `keyPressed`, popup preview appears (letters only) | fill → `keyPressedAlt` | fill darkens 12 % toward black (light) / lightens 12 % toward white (dark) |
| Long-press | popup per §3.4; key stays pressed-fill | repeat for ⌫ (initial 400ms, then 50ms interval) | opens voice settings sheet |
| Disabled (n/a in v1) | 38 % alpha | 38 % alpha | — |

Pressed fill applies instantly (0ms in); releases with 100ms fade-out, `Standard` easing.
Key preview popup: appears at 0ms, dismisses 60ms after release, 80ms fade.

**Shift iconography** (`keySurfaceAlt` key):
- **Off:** outline up-arrow ⇧ (2dp stroke, `keyText`); labels lowercase.
- **On (one-shot):** filled up-arrow, `accent` colored glyph; labels uppercase; reverts
  after next letter.
- **Caps lock** (double-tap ≤ 300ms): filled up-arrow with underline bar beneath, glyph
  `accent`, key fill gains 2dp inner bottom border in `accent`; labels uppercase until
  tapped again.

Auto-capitalize sets one-shot shift at sentence starts (visual identical to On).

---

## 6. Suggestion Strip

- 44dp tall, `suggestionBg`, hairline bottom divider as in §4.1.
- **3 slots**, equal thirds, each a full-height touch target, text centered,
  `vb.type.suggestion`, single line, ellipsize end.
- **Center slot = best candidate.** When autocorrect will apply on space/punctuation,
  center text renders in `suggestionAutocorrect` at weight 500. Left = verbatim typed
  text (when it differs), right = second candidate.
- 1dp vertical dividers between slots at `keyTextSecondary` 20 % alpha.
- **Overflow:** no chevron/expander in v1 — exactly 3 candidates, extras dropped.
- Long-press a suggestion: popup "Remove suggestion?" with **Remove** action (deletes
  from personal dictionary).
- **During voice:** the strip is **hidden** — the voice bar (with its transcript zone)
  replaces the entire keyboard+strip stack; nothing floats above it.
- Empty state (no composing text): shows nothing (blank strip, keeps height stable).
- In password/no-suggestion fields the strip collapses to 0dp with a 150ms height
  animation, `Standard` easing.

---

## 7. Onboarding (Compose / Material 3)

Five screens, linear pager with M3 `LinearProgressIndicator` (accent) under the top app
bar. All primary buttons: M3 filled, accent. Body: `bodyLarge`. Titles: `headlineMedium`.

**Screen 1 — Welcome**
- Title: "Type with your voice."
- Body: "VBoard turns speech into clean, punctuated text — entirely on your phone.
  Nothing you say ever leaves your device."
- Illustration: orb motif. Button: "Get started". Text button: "Learn how privacy works" → Screen 5 content as sheet.

**Screen 2 — Enable VBoard**
- Title: "Turn on VBoard"
- Body: "Android needs you to enable new keyboards in Settings. We'll take you there —
  just switch on **VBoard**."
- Button: "Open keyboard settings" → `ACTION_INPUT_METHOD_SETTINGS`. On return, if
  enabled, auto-advance; else inline caption: "VBoard isn't enabled yet."

**Screen 3 — Switch to VBoard**
- Title: "Make VBoard your keyboard"
- Body: "Choose VBoard as your current keyboard. You can switch back anytime from the
  keyboard icon in your navigation bar."
- Button: "Choose VBoard" → `InputMethodManager.showInputMethodPicker()`. Auto-advance
  on selection.

**Screen 4 — Microphone**
- Title: "Let VBoard hear you"
- Body: "Voice typing needs the microphone. Audio is processed on this device and never
  recorded, stored, or uploaded."
- Button: "Allow microphone" → runtime permission. If denied twice: caption
  "You can enable this later in Settings → Apps → VBoard → Permissions." and button
  becomes "Open app settings". Skippable via "Not now" text button (keyboard-only mode).

**Screen 5 — Download voice models**
- Title: "Download the voice engine"
- Body: "VBoard needs about **1 GB** of models for speech recognition and text cleanup.
  Download once, then everything works offline."
- Per-model rows (M3 list items): "Speech recognition — 780 MB", "Text cleanup — 240 MB",
  each with its own linear progress, state text (Queued / Downloading 43 % / Paused /
  Failed — Retry / Done ✓), and a pause/resume icon button; a Retry button on failure.
- Not on Wi-Fi: warning banner "You're on mobile data. This download is about 1 GB."
  with "Wait for Wi-Fi" (default) and "Download anyway" buttons.
- Downloads continue in background (foreground service + notification with same progress).
- Button: "Start download" → becomes "Continue in background". When both models finish:
  full-screen success state — Title "You're all set." Body "Tap the mic key anytime to
  start talking." Button "Done".

---

## 8. Settings IA

Compose/M3 preference screens. Groups and defaults:

**Appearance**
- Theme: System / Light / Dark — default **System**
- Dynamic color (Material You accent): switch — default **Off**
- Key borders: switch (draws 1dp `keyTextSecondary` 25 % outlines) — default **Off**
- Number row (dedicated 5th row): switch — default **Off**

**Typing**
- Auto-capitalize: switch — **On**
- Double-space period: switch — **On**
- Autocorrect: None / Modest / Aggressive — default **Modest**
  (Modest: corrects only high-confidence; Aggressive: always applies center candidate)
- Show suggestions: switch — **On**
- Long-press delay: Short 250ms / Default 400ms / Long 600ms — **Default**

**Feedback**
- Haptic feedback: switch — **On**; Strength: Light / Medium / Strong — **Medium**
  (maps to amplitude scale 0.5 / 0.75 / 1.0 where supported)
- Key sound: switch — **Off**; Volume: slider 0–100 — **60**

**Voice typing**
- Auto-punctuation: switch — **On**
- Remove filler words ("um", "uh", "like"): switch — **On**
- Smart cleanup (AI refinement): switch — **On**. Subtitle: "Fixes grammar and
  formatting after you speak. Adds about half a second before text is final. Runs
  entirely on-device."
- Raw transcript mode: switch — **Off**. Subtitle: "Insert exactly what the recognizer
  hears, with no cleanup." (Turning this On disables the three toggles above, greyed.)
- Auto-send timeout: Off / 5s / 8s / 15s of silence — **8s**
- Manage voice models → Model manager screen: per-model rows (name, version, size,
  Update / Delete), storage total, "Re-download all", and "Delete all models" (error
  color, confirm dialog).

**Privacy**
- Static screen restating: "All typing and voice processing happens on this device.
  VBoard has no network permission for keystrokes or audio. Model downloads are the
  only network activity." Link: privacy policy.

**About** — version, licenses, feedback link.

---

## 9. Motion & Sound

Easing tokens (M3): `Standard (0.2, 0, 0, 1)`, `EmphasizedDecelerate (0.05, 0.7, 0.1, 1)`,
`EmphasizedAccelerate (0.3, 0, 0.8, 0.15)`.

| Animation | Duration | Easing |
|---|---|---|
| Key pressed-fill out | 100ms | Standard |
| Key preview in / out | 0 / 80ms | — / Standard |
| Long-press popup in | 120ms scale 0.9→1 + fade | EmphasizedDecelerate |
| Layer switch (ABC↔?123↔=\<) | 0ms (instant swap) | — |
| Emoji panel in/out | 200 / 150ms slide+fade | EmphasizedDecelerate / -Accelerate |
| Keyboard→voice bar | 250ms | EmphasizedDecelerate |
| Voice bar→keyboard | 200ms | EmphasizedAccelerate |
| Orb breath loop | 1200ms | sine |
| Halo attack / release | 50 / 300ms | linear / Standard |
| Word finalize recolor | 120ms (+20ms stagger) | Standard |
| Strip collapse/expand | 150ms | Standard |

Haptics (typing): letter/symbol keys `HapticFeedbackConstants.KEYBOARD_TAP` on down;
delete repeat `KEYBOARD_TAP` every repeat tick; shift→caps-lock `CONTEXT_CLICK`;
long-press popup open `LONG_PRESS`; voice events per §4.3. All haptics gated by the
Feedback settings and `View.performHapticFeedback` (respect system settings).

Sound: `AudioManager.playSoundEffect(FX_KEYPRESS_STANDARD)` (spacebar `FX_KEYPRESS_SPACEBAR`,
delete `FX_KEYPRESS_DELETE`, return `FX_KEYPRESS_RETURN`) only when Key sound is On.

---

## 10. Accessibility

- **Contrast (computed, WCAG relative luminance):**
  - Light: `keyText #1B1C1E` on `keySurface #FFFFFF` = **17.0:1** ✓ (AAA)
  - Dark: `keyText #E8EAED` on `keySurface #24262B` = **12.6:1** ✓ (AAA)
  - Light: `onAccent #FFFFFF` on `accent #007A70` = **5.2:1** ✓ (AA)
  - Dark: `onAccent #00332E` on `accent #35D0C2` = **7.2:1** ✓ (AAA)
  - `keyTextSecondary` is decorative/hint only; never sole carrier of meaning.
- **Content descriptions:** every key exposes `contentDescription` — letters announce
  the character ("A"), function keys announce action ("Shift", "Shift on",
  "Caps lock on", "Delete", "Symbols", "Letters", "Emoji", "Space", "Enter" or the
  editor action name, "Voice typing"). Mic key: "Voice typing. Double-tap to speak."
- **TalkBack / explore-by-touch:** lift-to-type — touching a key announces it; lifting
  commits (standard IME a11y via `AccessibilityNodeProvider` over the custom View).
  Long-press popups are navigable nodes; popup candidates announced individually.
- **Voice states announced** via `announceForAccessibility`: "Listening", "Processing",
  finalized text is read back once committed, errors read verbatim from §4.2 copy.
  The orb is a button: "Stop listening, button."
- Key preview popups are suppressed when TalkBack is active (they occlude touch
  exploration); haptics retained.
- All touch targets ≥ 48dp (§2). Emoji cells expose Unicode CLDR names.
- Onboarding/settings: standard Compose semantics, headings marked with
  `Modifier.semantics { heading() }`, progress bars expose `stateDescription`
  ("Downloading, 43 percent").

---

*End of spec. Direct questions to design; deviations require a spec PR.*
