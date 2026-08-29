# VBoard — voice-first Android keyboard

VBoard is a Gboard-style Android keyboard (IME) where **voice is the primary way
to type**, with the "superpowers" of desktop dictation apps like Superwhisper:
ums, false starts, and self-corrections are removed automatically, punctuation
and casing are fixed, and an optional on-device LLM rewrites each utterance
toward what you *meant* — all **100% on-device**. Audio never leaves the phone.

## How it works

```
mic ──► AudioRecord (16 kHz mono)
          │
          ├─► streaming Zipformer 20M (sherpa-onnx) ── live partial words in the voice bar
          │        │ endpoint (0.8 s trailing silence)
          │        ▼
          └─► Parakeet TDT 0.6B v2 int8 (sherpa-onnx) ── accurate final transcript
                   │
                   ▼
             Rules cleanup (core) ── fillers, "no wait →" self-corrections,
                   │                 stutters, spoken punctuation, casing
                   ▼
             commit to the text field
                   │  (optional, off by default)
                   ▼
             Qwen2.5 0.5B .task (MediaPipe LLM) ── intent-preserving rewrite,
                                                   replaces the utterance in place
```

Typing fallback is a polished English QWERTY: autocorrect + suggestions backed
by a 50k-word SUBTLEX frequency lexicon, long-press accents/symbols, emoji
panel, spacebar cursor control, double-space period, themes, haptics.

## Modules

| Module | What | Where it runs |
|---|---|---|
| `core` | Pure-JVM Kotlin: transcript cleanup engine, suggestion/autocorrect engine, dictation state machine, resumable model downloader. ~250 unit tests. | Any JVM (`./gradlew -Pvboard.skipAndroid=true :core:test`) |
| `app` | Android IME: keyboard/voice-bar UI, sherpa-onnx + MediaPipe integration, model download service, Compose onboarding & settings. | Android 10+ (minSdk 29) |

## Building

```bash
./gradlew :app:assembleDebug          # full build (needs Android SDK)
./gradlew -Pvboard.skipAndroid=true :core:test   # logic tests only, no SDK needed
```

CI (GitHub Actions) assembles the APK, runs every test, and uploads the debug
APK as an artifact on each push.

### Models

Models are **not** bundled (APK stays small); the onboarding flow downloads
them over Wi-Fi with resume support and SHA-256 verification hooks:

| Pack | Size | Source |
|---|---|---|
| Live transcription — streaming Zipformer 20M int8 | ~122 MB | sherpa-onnx `asr-models` release |
| High accuracy — NVIDIA Parakeet TDT 0.6B v2 int8 | ~460 MB | sherpa-onnx `asr-models` release |
| Smart cleanup (optional) — Qwen2.5-0.5B-Instruct q8 `.task` | ~550 MB | LiteRT community (ungated, Apache-2.0) |

Prefer Gemma 3 1B for refinement? It needs a Hugging Face license acceptance,
so it can't be a default download — swap the URL in
`core/src/main/kotlin/com/vboard/core/model/ModelCatalog.kt` and sideload.

## Privacy

- All speech recognition and LLM refinement run on-device.
- The `INTERNET` permission is used **only** by the model downloader.
- Password fields hard-disable voice input, suggestions, and learning.
- Learned words stay in app-private storage and can be cleared in Settings.
- The clipboard classifier holds one-time codes, card numbers and passwords in
  memory for 60 seconds instead of writing them to the history file.

### Known limitations in the above

Two of those guarantees have measured holes. Both are pinned by tests and
scheduled as Wave 1.5; stating them here rather than only in the audit, because
a privacy claim with a known exception is worse than no claim.

- **The clipboard classifier is ASCII-only** (`VB-QA-24`). It detects digit runs
  with a regex that does not match Arabic-Indic, Devanagari, Persian or
  full-width digits, so a one-time code or card number copied in those digits is
  classified as ordinary text and **is written to the clipboard history file**.
- **Cleanup still capitalizes inside password fields** (`VB-QA-29`). Voice,
  suggestions and learning are disabled there as stated, but the field-kind flag
  gates only the first word, so text after a `.`, `!`, `?` or line break is still
  auto-capitalized in a field the spec says to leave untouched.

See [`docs/QA_REPORT.md`](docs/QA_REPORT.md) for the full defect list.

## Docs

- [`docs/PRODUCT_SPEC.md`](docs/PRODUCT_SPEC.md) — requirements (VB-###), NFRs, scope
- [`docs/DESIGN_SPEC.md`](docs/DESIGN_SPEC.md) — palette, metrics, motion, copy
- [`docs/QA_REPORT.md`](docs/QA_REPORT.md) — test traceability, findings, manual test plan
