package com.vboard.core.suggest

/**
 * Physical adjacency on a standard staggered QWERTY layout. Substituting a letter for a
 * neighboring key is the dominant fat-finger error, so the fuzzy matcher charges such
 * substitutions a reduced cost ([ADJACENT_SUBSTITUTION_COST]).
 */
internal object QwertyLayout {

    const val ADJACENT_SUBSTITUTION_COST = 0.6

    private val NEIGHBORS: Map<Char, String> = mapOf(
        'q' to "was",
        'w' to "qeasd",
        'e' to "wrsdf",
        'r' to "etdfg",
        't' to "ryfgh",
        'y' to "tughj",
        'u' to "yihjk",
        'i' to "uojkl",
        'o' to "ipkl",
        'p' to "ol",
        'a' to "qwszx",
        's' to "qweadzxc",
        'd' to "wersfxcv",
        'f' to "ertdgcvb",
        'g' to "rtyfhvbn",
        'h' to "tyugjbnm",
        'j' to "yuihknm",
        'k' to "uiojlm",
        'l' to "iopk",
        'z' to "asx",
        'x' to "asdzc",
        'c' to "sdfxv",
        'v' to "dfgcb",
        'b' to "fghvn",
        'n' to "ghjbm",
        'm' to "hjkn",
    )

    /** Fast bitset lookup: adjacency[c - 'a'] has bit (d - 'a') set when c and d neighbor. */
    private val adjacencyBits = IntArray(26).also { bits ->
        for ((c, neighbors) in NEIGHBORS) {
            for (d in neighbors) {
                bits[c - 'a'] = bits[c - 'a'] or (1 shl (d - 'a'))
            }
        }
    }

    fun adjacent(a: Char, b: Char): Boolean {
        if (a !in 'a'..'z' || b !in 'a'..'z') return false
        return (adjacencyBits[a - 'a'] shr (b - 'a')) and 1 == 1
    }
}
