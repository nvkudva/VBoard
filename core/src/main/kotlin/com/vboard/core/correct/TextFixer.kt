package com.vboard.core.correct

import com.vboard.core.text.FieldKind
import com.vboard.core.text.TranscriptCleaner

/** Whether a fix produced anything, and if not, why. */
enum class FixStatus {
    /** The field text changed. */
    APPLIED,

    /** Nothing was wrong; the field is left alone. */
    UNCHANGED,

    /** The operation was declined for this field — see [FixResult.refusal]. */
    REFUSED,
}

/** Why "AI fix" will not run at all. */
enum class FixRefusal {
    /**
     * Password fields. Never, under any setting: field content must not be read,
     * held in a snapshot, or handed to a model (VB-702, VB-901).
     */
    PASSWORD_FIELD,

    /**
     * Email address and URI fields. "Correcting" an address is almost always
     * wrong — the value is not prose, and a plausible-looking edit to it is worse
     * than a visible typo because it silently still looks like an address.
     */
    ADDRESS_FIELD,

    /** Numeric and phone fields: same reasoning, plus there is no grammar here. */
    NUMERIC_FIELD,

    /** Nothing to fix. */
    EMPTY_FIELD,

    /** Beyond [FixChunker.MAX_FIELD_CHARS]. */
    TOO_LONG,
}

/** What the optional smart tier managed to contribute. */
enum class SmartTier {
    /** Every chunk was refined and accepted. */
    APPLIED,

    /** Some chunks were refined; the rest fell back to rules-only. */
    PARTIAL,

    /** The model answered, and every answer failed validation. */
    REJECTED,

    /** The LLM pack is not installed. */
    NOT_INSTALLED,

    /** The pack is installed but would not load or threw. */
    UNAVAILABLE,

    /** The model did not answer inside its budget. */
    TIMED_OUT,

    /** The text is past [FixChunker.MAX_SMART_CHARS], or is one huge sentence. */
    TOO_LONG,

    /** Not attempted (refused field, or the tier is switched off). */
    OFF,
}

/** How a [SmartRefiner] failed, when it did. */
enum class SmartFailure { NOT_INSTALLED, LOAD_FAILED, TIMED_OUT, ERROR }

/**
 * One model call's outcome. Not a data class — the text is user content.
 */
class SmartOutput private constructor(
    private val text: String?,
    val failure: SmartFailure?,
) {
    fun text(): String? = text

    override fun toString(): String = "SmartOutput(failure=$failure, produced=${text != null})"

    companion object {
        fun of(text: String): SmartOutput = SmartOutput(text, null)
        fun failed(failure: SmartFailure): SmartOutput = SmartOutput(null, failure)
    }
}

/**
 * The platform's LLM pass, seen from core. The app supplies one backed by
 * MediaPipe; tests supply fakes.
 */
fun interface SmartRefiner {
    suspend fun refine(text: String): SmartOutput
}

/**
 * The result of one "AI fix". Not a data class: [correctedText] is user content
 * and must not appear in a generated `toString()` (VB-238).
 */
class FixResult internal constructor(
    val status: FixStatus,
    val refusal: FixRefusal?,
    val smart: SmartTier,
    private val text: String,
    /** Validator verdicts for chunks whose model output was thrown away. */
    val rejections: List<RejectReason>,
    val chunks: Int,
    /**
     * What changed, attributed to the tier that changed it, addressed against
     * [correctedText]. Mechanical edits are meant to land without ceremony;
     * editorial ones are meant to be shown and individually revertible.
     */
    val edits: List<FixEdit> = emptyList(),
) {
    fun correctedText(): String = text

    val mechanicalCount: Int get() = edits.count { it.kind == EditKind.MECHANICAL }

    val editorialCount: Int get() = edits.count { it.kind == EditKind.EDITORIAL }

    override fun toString(): String =
        "FixResult(status=$status, refusal=$refusal, smart=$smart, chunks=$chunks, " +
            "rejections=$rejections, mechanical=$mechanicalCount, editorial=$editorialCount)"
}

/**
 * "AI fix", end to end and free of Android (VB-231).
 *
 * The order is deliberate and never varies: gate the field, run the
 * deterministic pass, then — only if there is a refiner, and only within the
 * length policy — offer the *already cleaned* text to the model in reassemblable
 * chunks, validating each answer and falling back to the rules-only text
 * whenever one fails.
 *
 * The deterministic pass therefore always runs. There is no path through this
 * class where the user taps the button and nothing at all happens: either the
 * field was refused (and the caller says why), or the rules ran.
 */
class TextFixer(private val cleaner: TranscriptCleaner = TranscriptCleaner()) {

    /** True when the button should be tappable for this field at all (VB-233). */
    fun isEnabledFor(fieldKind: FieldKind): Boolean =
        fieldKind == FieldKind.TEXT || fieldKind == FieldKind.SEARCH

    /** The reason to decline, or null when the fix may proceed. */
    fun refusalFor(fieldKind: FieldKind, text: String): FixRefusal? = when {
        fieldKind == FieldKind.PASSWORD -> FixRefusal.PASSWORD_FIELD
        fieldKind == FieldKind.EMAIL || fieldKind == FieldKind.URI -> FixRefusal.ADDRESS_FIELD
        fieldKind == FieldKind.NUMBER -> FixRefusal.NUMERIC_FIELD
        text.isBlank() -> FixRefusal.EMPTY_FIELD
        text.length > FixChunker.MAX_FIELD_CHARS -> FixRefusal.TOO_LONG
        else -> null
    }

    /** The deterministic pass on its own; also the fallback for every failure. */
    fun rulesOnly(text: String, fieldKind: FieldKind = FieldKind.TEXT): String =
        TypedTextCleanup.clean(text, fieldKind, cleaner)

    /**
     * Corrects [text]. Suspends only inside [refiner]; cancelling the calling
     * coroutine abandons the fix without touching anything.
     *
     * Passing a null [refiner] is the "smart cleanup isn't downloaded" path and
     * still returns the rules-only correction.
     */
    suspend fun fix(
        text: String,
        fieldKind: FieldKind,
        refiner: SmartRefiner?,
    ): FixResult {
        refusalFor(fieldKind, text)?.let { refusal ->
            return FixResult(FixStatus.REFUSED, refusal, SmartTier.OFF, text, emptyList(), 0)
        }

        val rules = rulesOnly(text, fieldKind)
        if (refiner == null) {
            return finish(text, rules, rules, SmartTier.NOT_INSTALLED, emptyList(), 0)
        }
        if (rules.length > FixChunker.MAX_SMART_CHARS) {
            return finish(text, rules, rules, SmartTier.TOO_LONG, emptyList(), 0)
        }

        val segments = FixChunker.split(rules)
        val bodies = ArrayList<String>(segments.size)
        val rejections = mutableListOf<RejectReason>()
        var attempted = 0
        var refined = 0
        var skipped = 0
        var failure: SmartFailure? = null

        for (segment in segments) {
            val body = segment.body()
            if (!segment.refinable) {
                if (body.isNotBlank()) skipped++
                bodies.add(body)
                continue
            }
            attempted++
            val output = refiner.refine(body)
            val outputFailure = output.failure
            if (outputFailure != null) {
                failure = outputFailure
                bodies.add(body)
                continue
            }
            val verdict = RefinementValidator.validate(body, output.text())
            val accepted = verdict.text()
            if (verdict.accepted && accepted != null) {
                bodies.add(accepted)
                refined++
            } else {
                verdict.reason?.let(rejections::add)
                bodies.add(body)
            }
        }

        val tier = when {
            attempted == 0 -> SmartTier.TOO_LONG
            refined == attempted && skipped == 0 -> SmartTier.APPLIED
            refined > 0 -> SmartTier.PARTIAL
            failure == SmartFailure.NOT_INSTALLED -> SmartTier.NOT_INSTALLED
            failure == SmartFailure.TIMED_OUT -> SmartTier.TIMED_OUT
            failure != null -> SmartTier.UNAVAILABLE
            else -> SmartTier.REJECTED
        }
        return finish(
            original = text,
            rules = rules,
            corrected = FixChunker.assemble(segments, bodies),
            tier = tier,
            rejections = rejections,
            chunks = attempted,
        )
    }

    private fun finish(
        original: String,
        rules: String,
        corrected: String,
        tier: SmartTier,
        rejections: List<RejectReason>,
        chunks: Int,
    ): FixResult = FixResult(
        status = if (corrected == original) FixStatus.UNCHANGED else FixStatus.APPLIED,
        refusal = null,
        smart = tier,
        text = corrected,
        rejections = rejections,
        chunks = chunks,
        edits = FixAttribution.attribute(original, rules, corrected),
    )
}
