package com.superiorsoftllc.infantry.ui.model

/** What [filterRanked] searches in one item: a stable [id], a human [label] and the [group] it sits in. */
data class SearchText(val id: String, val label: String = id, val group: String = "")

/** Characters that start a new "segment" of an id: `anthropic/claude-sonnet-4.5` has four. */
private val SEGMENT_SEPARATORS = charArrayOf('/', '-', '.', ':', '_')

private val WHITESPACE = Regex("\\s+")

/**
 * ROLE-FIRST S6: the type-to-filter behind every searchable list (docs/INFANTRY-ROLE-FIRST.md section 7.1).
 *
 * The [query] is split on whitespace; EVERY term must match somewhere (AND), case-insensitively, as a
 * substring of the item's id, label or group. An item is ranked by its weakest term, best first:
 *
 *  0. the term is the whole id
 *  1. the term starts an id segment (`son` in `claude-sonnet-4`)
 *  2. the term starts the label
 *  3. the term is a substring of the id or label
 *  4. the term is a substring of the group only
 *
 * Items of equal rank keep the order they came in (the daemon's), and a blank query returns [items]
 * untouched. No fuzzy or subsequence matching: AND-substring already makes `son 4` find `claude-sonnet-4`,
 * and fuzzy matching would need a threshold to keep noise out.
 */
fun <T> filterRanked(items: List<T>, query: String, text: (T) -> SearchText): List<T> {
    val terms = query.trim().lowercase().split(WHITESPACE).filter { it.isNotEmpty() }
    if (terms.isEmpty()) return items
    val ranked = ArrayList<Pair<Int, T>>()
    for (item in items) {
        val t = text(item)
        val id = t.id.lowercase()
        val label = t.label.lowercase()
        val group = t.group.lowercase()
        var weakest = 0
        var matchesAll = true
        for (term in terms) {
            val tier = tierOf(term, id, label, group)
            if (tier == null) { matchesAll = false; break }
            if (tier > weakest) weakest = tier
        }
        if (matchesAll) ranked += weakest to item
    }
    // sortedBy is stable, so equal tiers keep the incoming order.
    return ranked.sortedBy { it.first }.map { it.second }
}

private fun tierOf(term: String, id: String, label: String, group: String): Int? = when {
    id == term -> 0
    startsSegment(id, term) -> 1
    label.startsWith(term) -> 2
    term in id || term in label -> 3
    term in group -> 4
    else -> null
}

private fun startsSegment(id: String, term: String): Boolean {
    var at = id.indexOf(term)
    while (at >= 0) {
        if (at == 0 || id[at - 1] in SEGMENT_SEPARATORS) return true
        at = id.indexOf(term, at + 1)
    }
    return false
}
