package com.superiorsoftllc.infantry.ui.plugin.config

import com.superiorsoftllc.infantry.ui.ConfigEntry
import com.superiorsoftllc.infantry.ui.configSectionNode
import kotlinx.serialization.json.JsonPrimitive

/**
 * P1 step 2 (docs/BLOCKS-LAYOUT-DESIGN.md 6s B): core's `config.json`, the first layer of the merged tree. Kept as text
 * in the common source set so web and Android read the very same file as the desktop (6s G); the format is a module's
 * module.json `config` list. P1 step 2.3: each tab strip's `sorter` (6s D), exposed, defaulting to the declared order.
 */
object CoreConfig {
    private const val BASE: String = """[
  { "node": "infantry.shell.center.chat.tabs", "group": "Tabs",
    "fields": [ { "key": "sorter", "type": "implementation", "label": "Sort the centre tabs", "registry": "tabs.sorter", "default": "byOrder", "exposed": true } ] },
  { "node": "infantry.shell.right.body.strip", "group": "Tabs",
    "fields": [ { "key": "sorter", "type": "implementation", "label": "Sort the right panel tabs", "registry": "tabs.sorter", "default": "byOrder", "exposed": true } ] }"""

    /**
     * CONFIG-5 (docs/CONFIG-MANIFEST-DESIGN.md section 4, step 6): the trust sections stay core and locked, declared as
     * `block` cards (the hand-written section is the card) placed by tab / order; copy and keywords come from [ConfigEntry].
     */
    val TRUST: List<ConfigEntry> = listOf(
        ConfigEntry.POLICY, ConfigEntry.PLUGINS, ConfigEntry.MODULES, ConfigEntry.REGISTRY, ConfigEntry.SECRETS, ConfigEntry.PAIR,
        ConfigEntry.ABOUT, ConfigEntry.UPDATES, ConfigEntry.SERVER_UPDATE, ConfigEntry.LOGS,
    )

    /**
     * CONFIG-6: the two sections the shell itself owns (which agents the sidebar lists; the daemon's keep-alive), declared
     * here as `block` cards. Lifecycle's value is the daemon's (`keepAlive.minutes`); `scope:"daemon"` waits for CONFIG-8.
     */
    val SHELL_OWNED: List<ConfigEntry> = listOf(ConfigEntry.AGENT_SCOPE, ConfigEntry.LIFECYCLE)

    private fun shellOwnedJson(): String = SHELL_OWNED.joinToString(",\n") { e ->
        val node = configSectionNode(e)
        fun q(t: String) = JsonPrimitive(t).toString()
        """  { "node": ${q(node)}, "group": ${q(e.title)}, "title": ${q(e.title)}, "tab": ${q(e.tab.label)}, "order": ${(ConfigPlan.MOVED.indexOf(e) + 1) * 10},""" +
            """ "keywords": [${e.keywords.joinToString(", ") { q(it) }}], "block": ${q(node)} }"""
    }

    private fun trustJson(): String = TRUST.joinToString(",\n") { e ->
        val node = configSectionNode(e)
        fun q(t: String) = JsonPrimitive(t).toString()
        """  { "node": ${q(node)}, "group": ${q(e.title)}, "title": ${q(e.title)}, "tab": ${q(e.tab.label)}, "order": ${ConfigEntry.entries.indexOf(e) * 10},""" +
            """ "applies": ${q(e.applies)}, "keywords": [${e.keywords.joinToString(", ") { q(it) }}], "block": ${q(node)}, "locked": true }"""
    }

    val JSON: String = BASE + ",\n" + shellOwnedJson() + ",\n" + trustJson() + "\n]"
}
