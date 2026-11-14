// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.docs.daemon

import com.superiorsoftllc.infantry.daemon.api.DaemonApi
import com.superiorsoftllc.infantry.daemon.api.DaemonPlugin
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * DOCS-LOOKUP-1: the in-app docs lookup. The user-facing wiki pages are jar resources under `docs/`; they are offered on demand as
 * two capabilities, `docs.list` and `docs.show`, reached through `infantry_capability_call` and the `infantry docs list|show` CLI.
 * Nothing is injected into a prompt, persona, preamble or tool schema: a page costs tokens only when an agent asks for it.
 */
class DocsDaemonPlugin : DaemonPlugin {
    override fun start(api: DaemonApi) {
        val loader = DocsDaemonPlugin::class.java.classLoader
        api.capabilities.handle(LIST) { list(loader) }
        api.capabilities.handle(SHOW) { call -> show(loader, (call.args["page"] as? JsonPrimitive)?.contentOrNull) }
    }

    companion object {
        const val LIST = "docs.list"
        const val SHOW = "docs.show"
        private const val INDEX = "docs/index.txt"

        /** Page names, from the jar's `docs/` folder (the build copies the pages in; `docs/index.txt` lists them). */
        fun pages(loader: ClassLoader): List<String> =
            loader.getResourceAsStream(INDEX)?.bufferedReader()?.useLines { l -> l.map { it.trim() }.filter { it.isNotEmpty() }.toList() }.orEmpty()

        fun text(loader: ClassLoader, name: String): String? =
            loader.getResourceAsStream("docs/$name.md")?.use { String(it.readBytes(), Charsets.UTF_8) }

        fun titleOf(text: String, name: String): String =
            text.lineSequence().firstOrNull { it.startsWith("# ") }?.removePrefix("# ")?.trim() ?: name

        fun list(loader: ClassLoader): JsonObject = buildJsonObject {
            put("pages", buildJsonArray {
                pages(loader).forEach { n ->
                    add(buildJsonObject { put("page", n); put("title", titleOf(text(loader, n).orEmpty(), n)) })
                }
            })
        }

        fun show(loader: ClassLoader, page: String?): JsonObject {
            val want = page?.trim()?.removeSuffix(".md").orEmpty()
            require(want.isNotEmpty()) { "page is required ($LIST names the pages)" }
            val all = pages(loader)
            val name = all.firstOrNull { it.equals(want, ignoreCase = true) } ?: throw IllegalArgumentException(
                "no docs page '${want.take(60)}'" + if (all.isEmpty()) " (none bundled)" else "; available: ${all.joinToString(", ")}",
            )
            val text = text(loader, name) ?: throw IllegalArgumentException("docs page '$name' is missing from the bundle")
            return buildJsonObject { put("page", name); put("title", titleOf(text, name)); put("text", text) }
        }
    }
}
