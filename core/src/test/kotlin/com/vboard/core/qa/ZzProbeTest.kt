package com.vboard.core.qa

import com.vboard.core.suggest.AutocorrectMode
import com.vboard.core.suggest.Lexicon
import com.vboard.core.suggest.SuggestionEngine
import com.vboard.core.suggest.SuggestionRequest
import com.vboard.core.text.FieldKind
import org.junit.jupiter.api.Test

class ZzProbeTest {
    @Test
    fun probeAccentedAutocorrect() {
        val e = SuggestionEngine(Lexicon.english())
        val words = listOf(
            "naïve", "café", "résumé", "Straße", "über", "fiancée", "jalapeño",
            "Zoë", "Björk", "señor", "crème", "déjà", "piñata", "façade", "cliché",
            "Việt", "日本", "привет", "İzmir", "Müller", "Grüße", "élan", "à",
        )
        for (m in listOf(AutocorrectMode.CONSERVATIVE, AutocorrectMode.AGGRESSIVE)) {
            for (w in words) {
                val r = e.suggest(SuggestionRequest(w, null, FieldKind.TEXT, m))
                println("P5_ACC  $m |$w| ac=${r.autocorrect?.text} strip=${r.suggestions.map { it.text }}")
            }
        }
    }
}
