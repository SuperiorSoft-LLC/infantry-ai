package com.superiorsoftllc.agenthq.ui.plugin.config

/**
 * P1 step 2 (docs/BLOCKS-LAYOUT-DESIGN.md 6s B): core's `config.json`, the first layer of the merged tree. Kept as text
 * in the common source set so web and Android read the very same file as the desktop (6s G); the format is a module's
 * module.json `config` list. P1 step 2.3: each tab strip's `sorter` (6s D), exposed, defaulting to the declared order.
 */
object CoreConfig {
    const val JSON: String = """[
  { "node": "infantry.shell.center.chat.tabs", "group": "Tabs",
    "fields": [ { "key": "sorter", "type": "implementation", "label": "Sort the centre tabs", "registry": "tabs.sorter", "default": "byOrder", "exposed": true } ] },
  { "node": "infantry.shell.right.body.strip", "group": "Tabs",
    "fields": [ { "key": "sorter", "type": "implementation", "label": "Sort the right panel tabs", "registry": "tabs.sorter", "default": "byOrder", "exposed": true } ] }
]"""
}
