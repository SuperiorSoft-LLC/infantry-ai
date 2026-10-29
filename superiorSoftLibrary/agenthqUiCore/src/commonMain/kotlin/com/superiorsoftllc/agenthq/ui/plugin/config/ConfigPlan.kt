package com.superiorsoftllc.agenthq.ui.plugin.config

import com.superiorsoftllc.agenthq.plugin.api.ConfigDeclaration
import com.superiorsoftllc.agenthq.ui.ConfigEntry
import com.superiorsoftllc.agenthq.ui.ConfigTab
import com.superiorsoftllc.agenthq.ui.configSectionNode

/** One card of the generated dialog: a declaration with a `tab`, either a form (its fields) or a `block` escape hatch. */
class PlanCard(val module: String, val declaration: ConfigDeclaration, val entry: ConfigEntry?) {
    val tab: String get() = declaration.tab.orEmpty()
    val node: String get() = declaration.node
    val title: String get() = declaration.title ?: declaration.group
    val applies: String? get() = declaration.applies ?: entry?.applies
    val block: String? get() = declaration.block
    val locked: Boolean get() = declaration.locked
    val fields get() = declaration.fields.sortedBy { it.order }

    /** Unique per card: the highlight and the result row are keyed by it. */
    val key: String get() = "$node/${declaration.group}"

    internal fun haystack(): String =
        (listOf(title, applies.orEmpty(), tab) + declaration.keywords + declaration.fields.flatMap { listOf(it.label, it.description.orEmpty()) })
            .joinToString(" | ").lowercase()
}

/** A search hit: a plan card, or a hand-written section no card stands for. */
class ConfigHit(val key: String, val tab: String, val title: String, val applies: String, val entry: ConfigEntry?)

/**
 * CONFIG-5 (docs/CONFIG-MANIFEST-DESIGN.md section 4): what the Settings dialog shows when the daemon holds a compiled
 * manifest. Cards come from the manifest's declarations that have a `tab`; a hand-written [ConfigEntry] that no card
 * stands for still shows after them ([uncovered]), so the fallback sections keep working until CONFIG-6 moves them.
 * Tabs: core's default order ([ConfigTab]), then any other tab by first declaration; a tab with nothing to show is hidden.
 */
class ConfigPlan private constructor(val cards: List<PlanCard>, val uncovered: List<ConfigEntry>) {
    /** Tab labels in display order. */
    val tabs: List<String> = run {
        val used = (cards.map { it.tab } + uncovered.map { it.tab.label }).toSet()
        ConfigTab.entries.map { it.label }.filter { it in used } + cards.map { it.tab }.distinct().filter { t -> ConfigTab.entries.none { it.label == t } }
    }

    fun cardsOf(tab: String): List<PlanCard> = cards.filter { it.tab == tab }
    fun uncoveredOf(tab: String): List<ConfigEntry> = uncovered.filter { it.tab.label == tab }

    /** The tab label to show: [wanted] when it has something, else the first one (null: nothing shows). */
    fun pick(wanted: String?): String? = if (wanted != null && wanted in tabs) wanted else tabs.firstOrNull()

    /** The tab that draws hand-written [entry] (its card's tab when a card stands for it), null when it is not shown. */
    fun tabOf(entry: ConfigEntry): String? = cards.firstOrNull { it.entry == entry }?.tab ?: entry.tab.label.takeIf { entry in uncovered }

    /** Cards and sections where every word of [query] occurs in title, applies, tab, keywords, field labels or descriptions. */
    fun search(query: String): List<ConfigHit> {
        val words = query.trim().lowercase().split(' ', '\t').filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val out = mutableListOf<ConfigHit>()
        for (t in tabs) {
            for (c in cardsOf(t)) {
                val hay = c.haystack() + " | " + (c.entry?.keywords ?: emptyList()).joinToString(" | ").lowercase()
                if (words.all { it in hay }) out += ConfigHit(c.key, t, c.title, c.applies.orEmpty(), c.entry)
            }
            for (e in uncoveredOf(t)) {
                val hay = (listOf(e.title, e.applies, e.tab.label) + e.keywords).joinToString(" | ").lowercase()
                if (words.all { it in hay }) out += ConfigHit(e.name, t, e.title, e.applies, e)
            }
        }
        return out
    }

    companion object {
        /** CONFIG-6: the 11 once-ownerless sections, in display order; each now has an owner's declaration. */
        val MOVED: List<ConfigEntry> = listOf(
            ConfigEntry.THEME, ConfigEntry.BLUR, ConfigEntry.USER_PORTRAIT, ConfigEntry.MARKDOWN_ENGINE, ConfigEntry.REASONING,
            ConfigEntry.TAB_GROUPS, ConfigEntry.AGENT_SCOPE, ConfigEntry.PROGRESS_STALE, ConfigEntry.LIFECYCLE,
            ConfigEntry.CLI_LOCATIONS, ConfigEntry.TOOL_APPROVAL,
        )

        /**
         * [declarations] (the snapshot's), [present] (the hand-written sections this host has) and [blockIds] (the ids of the
         * fills the dialog can draw): cards with fields, block cards whose fill exists, and the sections left over.
         */
        fun of(declarations: List<ModuleDeclaration>, present: Set<ConfigEntry>, blockIds: Set<String>): ConfigPlan {
            val cards = declarations.mapNotNull { m ->
                val d = m.declaration
                when {
                    d.tab == null -> null
                    d.block != null -> {
                        val entry = ConfigEntry.entries.firstOrNull { configSectionNode(it) == d.block }
                        val there = if (entry != null) entry in present else d.block in blockIds
                        if (there) PlanCard(m.module, d, entry) else null
                    }
                    d.fields.isEmpty() -> null
                    else -> PlanCard(m.module, d, ConfigEntry.entries.firstOrNull { it.tab.label == d.tab && it.title == (d.title ?: d.group) })
                }
            }.sortedBy { it.declaration.order }
            val covered = cards.mapNotNull { it.entry }.toSet()
            // CONFIG-6: a MOVED section shows only through its owner's card, so removing the owner removes the section.
            return ConfigPlan(cards, ConfigEntry.entries.filter { it in present && it !in covered && it !in MOVED })
        }
    }
}
