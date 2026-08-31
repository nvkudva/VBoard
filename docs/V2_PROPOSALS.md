# VBoard V2 — Everything Proposed

Status: **captured for review. Nothing here is being built.**
Date: 2026-08-29

This is the raw material behind [PLAN.md](../PLAN.md): every idea product and design
proposed, what they argued about across two rounds, what each conceded, and what was cut.
The disagreements are recorded *as disagreements* rather than averaged, because that is
where most of the information is.

---

## 1. Where they converged, independently

Two reviews reached the same conclusion without seeing each other's work.

### The discarded second transcript

Every utterance is transcribed twice — a 20M streaming model, then a 0.6B accuracy model —
and the first is discarded the instant the second answers. Where two independently-trained
recognizers agree, the word is right; where they disagree, that word is the most likely
error. That is a calibrated per-word confidence signal for zero additional compute, and it
is structurally unavailable to a cloud keyboard running a single recognizer.

Product reached it from *"what can we do that Gboard cannot"*; design from *"what is the
20M model for, now that the accuracy model is mandatory"*. Both concluded it should be
built in `core`, pure and testable, **before** anything that consumes it — four other
proposals do.

### Draft rescue

Both independently proposed recovering dictation lost when the input connection dies, both
anchored on the same acknowledged `TODO` in `finishSession()`. The plan promotes this out
of the feature list entirely: it is a data-loss bug, not a feature.

### Clipboard over contacts

Both rejected contacts access for recognizer biasing in favour of harvesting names from
field text and clipboard history. Design's framing was the sharper one:

> The permission prompt *is* the message. A keyboard that already triggers Android's "may
> collect all the text you type" warning, then asks for the microphone, then asks for
> contacts, has spent its trust budget before the user types a word — regardless of the
> fact that everything is processed locally.

---

## 2. The joint list

Ranked by combined conviction. Provenance recorded because it matters for who defends
what.

| # | Idea | From | Cost | Notes |
|---|---|---|---|---|
| 1 | Two-model disagreement → per-word confidence | **both** | CHEAP–MED | Infrastructure. Build first. |
| 2 | Hold-to-talk + release-to-send | design | CHEAP | Product called it the best interaction-per-line on either list |
| 3 | Spoken-format intelligence — "four thirty" → 4:30pm, "twenty five bucks" → $25, spoken emails | design | CHEAP–MED | Must run *before* `ContentGuard` |
| 4 | Confusion learning — never repeats a correction you made | product | MED | Detection near-free once #1 exists |
| 5 | Universal undo ring + one-sentence attribution | both | CHEAP–MED | Two open disagreements (D1, D2) |
| 6 | Draft rescue | **both** | MED | Promoted to Wave 0 as a bug |
| 7 | Positional voice editing + tap-to-select-then-speak | design | MED | **Cut** — see D3 |
| 8 | Prosody-driven question marks | product | CHEAP–MED | **Cut** — see §5 |
| 9 | Spell-it-to-teach-it | product | MED | **Cut from V2**; returns standalone if beta justifies |
| 10 | Biased re-decode of disputed spans | design | MED | Only reachable after #1 |
| 11 | Clipboard-harvested names → recognizer biasing | both | CHEAP | Off by default; sensitive clips excluded |
| 12 | Register-aware punctuation | product | CHEAP | **Cut** — author ranked it lowest |
| 13 | Adaptive endpointing + telling the user first | product | CHEAP | Shrinks once #2 removes the endpoint from the common case |

---

## 3. The debate

### Design challenged product on three things

**Referential voice editing is not deliverable.** *"The target of a referential correction
is by definition a word the recognizer just got wrong, so it will get it wrong again
inside the correction command. Every demo works, because demos correct words the
recognizer heard fine; every real use fails."*

**Re-decoding buffered audio is a promise the physics won't keep.** Same audio, same
model, same configuration → same transcript. It ships as a button that appears to do
nothing.

**Session-scoped audio retention is a different product.** A rolling buffer is fine; *"the
keyboard that keeps a recording"* is a sentence we cannot afford in a review.

### Product's replies

**On referential editing — it inverted the diagnosis, and the inversion is sharper.** The
*target* is drawn from the recognizer's own output distribution, which is precisely what it
reproduces most reliably. It is the *replacement* — the novel word — that cannot be heard.
So the real failure is: hears "change pharmacy to pharmacy", target matches perfectly,
replacement is identical, field doesn't change, button appears broken. **And design's
proposed alternative inherits it**: an unambiguous touch solves the easy half and leaves
the hard half untouched.

**On re-decode — near-total concession.** Product's idea collapses into design's: the free
alternative is the second opinion we already hold, and the only version where "try again"
can honestly promise a different answer is a *biased* re-decode with hotwords boosted. It
also conceded session-scoped retention entirely, noting the requirement collapses to a
single utterance once the feature is scoped to "the span you just spoke".

**On the undo surface — mostly concession, two residuals.** A universal ring was not what
it was arguing against; it objected to a *review step*. It held that mechanical edits must
not get individual pills, citing the `EditKind` doc comment — *"nobody wants to be asked
about a capital letter, and everybody wants to be told when a word was swapped"* — and that
autocorrect should stay out of the ring.

### Design's concessions in round two

**On the AI-fix surface — conceded, then rejected product's own compromise.** It withdrew
its visible diff pills, agreeing that *"nobody proofreads their proofreader"*. But it
rejected the offered 4-second editorial highlight on product's own logic: *"too short to
review and long enough to alarm."* It also priced something nobody had — highlighting text
inside a host field requires holding a composing region over committed text, in a process
Android kills aggressively.

It held one point: **the count must be visible without a tap**, because *"the difference
between 4 fixes and 31 fixes is the difference between not caring and needing to look."*

**On voice bar height — it admitted conflating two claims.** The constraint was never
"must be tall", it is **"must be the same"**: any height delta reflows the host app twice
per dictation. That argument survives the transcript dying entirely. It withdrew the tall
scrollable transcript, and two of its own headline ideas as casualties.

**On the overflow menu — argued both sides lose**, and proposed something better: raw mode
is not a setting, it is a *state*, so the status badge itself becomes the control. One
element, visible, single tap, no junk drawer and no hidden gesture.

### The hole design found in product's safety rule

Product's VB-103 policy was "stream into the field only where multiline and no Send/Search
action". Design pointed out that **WhatsApp, Slack and Messages compose fields are
multiline with send as a separate in-app button** — so the rule streams into exactly the
three apps that matter most, and the catastrophe it was designed to prevent is fully
available. Verified and accepted.

---

## 4. Standing disagreements

Recorded rather than resolved by the participants; adjudicated in
[PLAN.md §4](../PLAN.md).

| | Product | Design |
|---|---|---|
| **D1** Mechanical edits in the undo ring | Editorial gets pills, mechanical gets a count at most | One ring, all changes equal |
| **D2** Autocorrect in the ring | Stays out — shipped muscle memory | Completeness |
| **D3** Tap-to-select-then-speak | Inherits the replacement bug | The touch removes the ambiguity |
| **D4** Voice bar chrome | Hold-to-talk makes it a minority path | Working-set transcript earns its place |
| **D5** Word error rate | Should not be in the room at all | Asked which number wins |

---

## 5. Cut, and why

**Referential voice editing + spelling escape hatch** — three speculative layers deep with
no user evidence, and the diagnosis in D3 is fatal to the feature as scoped.

**Prosody-driven question marks.** The strongest pushback in the whole review:

> *"150 lines of DSP, no model" is the tell.* Pitch-rise detection on 16kHz mono in real
> acoustic environments is a demo feature; it works at a desk and fails in a car, a bar, or
> with any speaker whose prosody isn't General American. Its failure mode is inserting a
> question mark into a statement — precisely the cleanup-overreach risk the conservative
> rule set exists to prevent.

The known limitation it was meant to fix — interjection-led questions receiving a period —
is better addressed by a twenty-line interjection allowlist: deterministic, testable, no
signal processing.

**Register-aware punctuation** (a period on a short message reads as curt) — charming, and
its own author ranked it lowest. Cut cleanly rather than carried.

**Unbiased re-decode as a standalone feature** — physics.

**Session-scoped audio retention** — privacy framing, conceded by its proposer.

---

## 6. Two questions the debate answered

**"What is the 20M streaming model for, now that the accuracy model is mandatory?"**
Four jobs, verified against the source: endpointing (which lives *entirely* on the
streaming recognizer — the offline config has none), liveness, the watchdog whose fallback
*is* the streaming partial, and now the second opinion. Cutting it is not a 128MB saving;
it means adding a VAD and rewriting segmentation. **It is a subsystem, not a line item.**

**"Which metric wins when accuracy and effort conflict?"**
Word error rate is disqualified as a prioritisation input by reductio: filler removal and
repetition collapse make WER *worse by construction*, so if WER ever won an argument the
implied action would be deleting the differentiator. Send-ready rate — the fraction of
dictated characters reaching a Send action with zero manual edits — measures what users
actually feel, and is computable from the IME's own event stream with nothing leaving the
device. It is also **not measurable until instrumentation ships**, which is why that is
Wave 0.
