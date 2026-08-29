package com.vboard.core.suggest

/**
 * Bounded LRU store of what this user actually types: unigram counts and
 * (previous, word) bigram counts. Words are normalized to lowercase. Each of the two
 * maps holds at most [maxEntries] entries; when full, the least-recently-used entry
 * is evicted.
 *
 * Not thread-safe; the IME confines it to its worker thread.
 */
class UserHistory(val maxEntries: Int = 5000) {

    private class LruMap(private val maxEntries: Int) :
        LinkedHashMap<String, Int>(64, 0.75f, /* accessOrder = */ true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>): Boolean =
            size > maxEntries
    }

    private val unigrams = LruMap(maxEntries)
    private val bigrams = LruMap(maxEntries)

    fun recordUnigram(word: String) {
        val w = normalize(word) ?: return
        unigrams[w] = (unigrams[w] ?: 0) + 1
    }

    fun recordBigram(previous: String, word: String) {
        val p = normalize(previous) ?: return
        val w = normalize(word) ?: return
        val key = p + KEY_SEPARATOR + w
        bigrams[key] = (bigrams[key] ?: 0) + 1
    }

    fun unigramCount(word: String): Int {
        val w = normalize(word) ?: return 0
        return unigrams[w] ?: 0
    }

    fun bigramCount(previous: String, word: String): Int {
        val p = normalize(previous) ?: return 0
        val w = normalize(word) ?: return 0
        return bigrams[p + KEY_SEPARATOR + w] ?: 0
    }

    /** All learned continuations of [previous], as word -> count. Engine-internal. */
    internal fun continuationsOf(previous: String): List<Pair<String, Int>> {
        val p = normalize(previous) ?: return emptyList()
        val prefix = p + KEY_SEPARATOR
        // Snapshot the entries first: this map is access-ordered, so lookups mutate it.
        val entries = bigrams.entries.map { it.key to it.value }
        return entries.mapNotNull { (key, count) ->
            if (key.startsWith(prefix)) key.substring(prefix.length) to count else null
        }
    }

    /**
     * Serializes to a stable line-oriented format, one entry per line, least-recently-used
     * first (so [restore] reproduces both counts and recency order):
     * `u<TAB>word<TAB>count` for unigrams, `b<TAB>previous<TAB>word<TAB>count` for bigrams.
     */
    fun snapshot(): String {
        val sb = StringBuilder(unigrams.size * 16 + bigrams.size * 24)
        for ((word, count) in unigrams.entries.map { it.key to it.value }) {
            sb.append("u\t").append(word).append('\t').append(count).append('\n')
        }
        for ((key, count) in bigrams.entries.map { it.key to it.value }) {
            val sep = key.indexOf(KEY_SEPARATOR)
            sb.append("b\t").append(key, 0, sep).append('\t')
                .append(key, sep + 1, key.length).append('\t').append(count).append('\n')
        }
        return sb.toString()
    }

    companion object {
        private const val KEY_SEPARATOR = '\u0001'

        private fun normalize(word: String): String? {
            val w = word.trim().lowercase()
            if (w.isEmpty()) return null
            if (w.any { it.isWhitespace() || it == KEY_SEPARATOR || it == '\t' }) return null
            return w
        }

        /** Rebuilds a history from a [snapshot] string. Malformed lines are ignored. */
        fun restore(serialized: String, maxEntries: Int = 5000): UserHistory {
            val history = UserHistory(maxEntries)
            for (line in serialized.lineSequence()) {
                if (line.isEmpty()) continue
                val parts = line.split('\t')
                when {
                    parts.size == 3 && parts[0] == "u" -> {
                        val count = parts[2].toIntOrNull() ?: continue
                        val w = normalize(parts[1]) ?: continue
                        if (count > 0) history.unigrams[w] = count
                    }
                    parts.size == 4 && parts[0] == "b" -> {
                        val count = parts[3].toIntOrNull() ?: continue
                        val p = normalize(parts[1]) ?: continue
                        val w = normalize(parts[2]) ?: continue
                        if (count > 0) history.bigrams[p + KEY_SEPARATOR + w] = count
                    }
                }
            }
            return history
        }
    }
}
