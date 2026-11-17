package org.fdroid.phone.search

import java.text.Normalizer
import java.util.Locale

/**
 * Matching rules for the store's search. Queries are compared against the app
 * text of every locale in the index, ignoring case and diacritics, so searching
 * "uber" finds "Über" and CJK queries match inside names without spaces.
 */
internal object SearchText {

    private val marks = "\\p{M}".toRegex()

    /** Lowercase, without diacritics, so accented and unaccented input both match. */
    fun normalize(value: CharSequence): String {
        val decomposed = Normalizer.normalize(value, Normalizer.Form.NFKD)
        return decomposed.replace(marks, "").lowercase(Locale.ROOT)
    }

    /** The store ignores one-character queries, except for ideographic scripts. */
    fun isSearchable(query: String): Boolean =
        query.isNotBlank() &&
            (query.trim().length >= 2 || query.any { Character.isIdeographic(it.code) })

    fun words(query: String): List<String> =
        normalize(query).split(' ').filter { it.isNotBlank() }

    fun matches(haystack: String, queryWords: List<String>): Boolean =
        queryWords.isNotEmpty() && queryWords.all { haystack.contains(it) }
}
