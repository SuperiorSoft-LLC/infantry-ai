// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon.plugins

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * DV-1a (docs/DECL-UI-DESIGN.md 2.5): the shape checks of a declarative view page. Pure functions over JSON: they know no
 * widget name, only size, object count and nesting.
 */
internal object ViewPages {
    /** One line saying which limit [page] breaks, or null when it is within all three. */
    fun refusal(page: JsonObject, maxBytes: Long, maxNodes: Long, maxDepth: Long): String? {
        val bytes = page.toString().length
        if (bytes > maxBytes) return "page is $bytes bytes, over limits.views.maxPageBytes ($maxBytes)"
        var nodes = 0L
        var tooDeep = false
        fun walk(e: JsonElement, depth: Long) {
            if (tooDeep) return
            if (depth > maxDepth) { tooDeep = true; return }
            when (e) {
                is JsonObject -> { nodes++; e.values.forEach { walk(it, depth + 1) } }
                is JsonArray -> e.forEach { walk(it, depth + 1) }
                else -> {}
            }
        }
        walk(page, 1)
        if (tooDeep) return "page nests deeper than limits.views.maxDepth ($maxDepth)"
        if (nodes > maxNodes) return "page has $nodes nodes, over limits.views.maxNodes ($maxNodes)"
        return null
    }

    /** The static layout file [layout] of the module folder [dir]; null when it escapes the folder or is not a file. */
    fun layoutFile(dir: File, layout: String): File? {
        val root = runCatching { dir.canonicalFile }.getOrNull() ?: return null
        val c = runCatching { File(dir, layout).canonicalFile }.getOrNull() ?: return null
        return c.takeIf { it.path.startsWith(root.path + File.separator) && it.isFile }
    }
}
