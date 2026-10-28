package com.superiorsoftllc.agenthq.ui.plugin.config

import com.superiorsoftllc.agenthq.ui.model.AppState
import com.superiorsoftllc.agenthq.ui.plugin.TabProvider

/** P1 step 2 (6s D): orders a strip's tabs; registered under a key of [TabSorters.REGISTRY], picked by a strip's `sorter` field. */
fun interface TabSorter {
    /** [tabs] in declared order, as this sorter orders them. */
    fun sort(tabs: List<TabProvider>): List<TabProvider>
}

/**
 * P1 step 2: the tab strips' `sorter` field, the first real implementation field. Core registers [BY_ORDER] (the
 * declared order, the default, so nothing moves until a user picks another) and [BY_TITLE]; a module registers more
 * with `UiRegistrar.implementation("tabs.sorter", key, sorter)`, declared under `implementations` in its module.json.
 */
object TabSorters {
    const val REGISTRY = "tabs.sorter"
    const val FIELD = "sorter"
    const val BY_ORDER = "byOrder"
    const val BY_TITLE = "byTitle"

    /** The strip label without its live badge or shortcut hint (read against an empty state, so it never changes). */
    fun label(t: TabProvider): String = t.title(AppState()).substringBefore(" ^").substringBefore(" (").trim().ifEmpty { t.id }

    /** Core's implementations, registry -> key -> value. */
    val core: Map<String, Map<String, Any>> = mapOf(
        REGISTRY to linkedMapOf(
            BY_ORDER to TabSorter { it },
            BY_TITLE to TabSorter { tabs -> tabs.sortedWith(compareBy({ label(it).lowercase() }, { it.id })) },
        ),
    )
}
