// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.scripting.daemon

import com.superiorsoftllc.infantry.daemon.api.downloads.DownloadRequest
import com.superiorsoftllc.infantry.daemon.api.downloads.Downloads
import com.superiorsoftllc.infantry.plugin.api.Progress
import com.superiorsoftllc.infantry.plugin.api.ProgressHandle
import com.superiorsoftllc.infantry.plugin.api.ProgressSpec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * CORE-PROGRESS-DOWNLOAD-1 (docs/wiki/Progress.md): the scripting side of the core progress system and downloader.
 *
 * - `progress.start` starts a task (it shows in every client's running-tasks list) and answers its id; a script run is short,
 *   so the task outlives it and a later run moves it with `progress.update` (a step, an amount, or the end: done / failed).
 * - `http.download` downloads one file into the project's `.infantry/` folder through the core downloader (retries, resume,
 *   size cap, sha256, temp file then atomic move, progress). Guarded like `http.fetch`: off until `scripting.fetch.enabled`,
 *   and only to a URL the allow-list `scripting.fetch.allow` names. Secret header values come from the secrets store by name.
 */
class ProgressBridge(
    private val progress: Progress,
    private val downloads: Downloads,
    private val config: (String) -> String?,
    /** Secret [name] for [project] as a field map, or null. */
    private val secret: (name: String, project: String?) -> Map<String, String>?,
    /** The absolute path of [path] under [project]'s `.infantry/` folder (refuses a path that leaves it). */
    private val projectPath: (project: String?, path: String) -> String,
    /** Ids of the projects that exist; a session-less caller may name one with the `project` arg. */
    private val projects: () -> Collection<String> = { emptyList() },
) {
    private val handles = ConcurrentHashMap<String, ProgressHandle>()

    fun start(args: JsonObject): JsonElement {
        val title = str(args["title"]) ?: throw IllegalArgumentException("title is required")
        val steps = (args["steps"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
        val h = progress.start(ProgressSpec(title, id = str(args["id"]).orEmpty(), steps = steps, unit = str(args["unit"]).orEmpty(), total = num(args["total"]) ?: -1))
        handles[h.id] = h
        return buildJsonObject { put("id", h.id) }
    }

    fun update(args: JsonObject): JsonElement {
        val id = str(args["id"]) ?: throw IllegalArgumentException("id is required")
        val h = handles[id] ?: throw IllegalArgumentException("no live progress task '$id' started by a script")
        str(args["step"])?.let { h.step(it) }
        num(args["done"])?.let { h.amount(it, num(args["total"]) ?: h.task.total, str(args["unit"]).orEmpty()) }
        when (str(args["state"])?.lowercase()) {
            null -> {}
            "done" -> h.done()
            "failed" -> h.fail(str(args["error"]) ?: "failed", retryable = false)
            "cancelled" -> h.cancelled()
            else -> throw IllegalArgumentException("state must be done, failed or cancelled")
        }
        if (h.task.finished) handles.remove(id)
        return buildJsonObject { put("id", id); put("state", h.task.state.name.lowercase()); put("cancelRequested", h.cancelRequested) }
    }

    fun download(args: JsonObject, callerProject: String?): JsonElement {
        // HTTP-DOWNLOAD-1: a caller bound to a project always uses it (the arg never redirects it); a session-less caller (MCP/CLI) names one.
        val project = callerProject ?: str(args["project"])?.also {
            if (it !in projects()) throw IllegalArgumentException("unknown project '$it' (see infantry_projects)")
        }
        if (project == null) throw IllegalArgumentException("http.download runs in a project (its .infantry/ folder): this caller has no session project, so pass args.project = <project id> (see infantry_projects)")
        val s = FetchSettings.read(config)
        if (!s.enabled) throw IllegalStateException("http.download is off. Enable it with the setting scripting.fetch.enabled = true and list hosts in scripting.fetch.allow")
        val url = str(args["url"]) ?: throw IllegalArgumentException("url is required")
        val uri = runCatching { URI(url) }.getOrNull()?.takeIf { it.scheme.equals("http", true) || it.scheme.equals("https", true) }
            ?: throw IllegalArgumentException("url must be an http(s) URL")
        if (!FetchBridge.allowed(s.allow, uri)) throw IllegalStateException("${uri.host} is not in the allow-list scripting.fetch.allow")
        val dest = File(projectPath(project, str(args["dest"]) ?: throw IllegalArgumentException("dest (a path under .infantry/) is required")))
        val headers = buildMap { (args["headers"] as? JsonObject)?.forEach { (k, v) -> str(v)?.let { put(k, it) } } }
        val secrets = buildMap {
            (args["secretHeaders"] as? JsonObject)?.forEach { (header, ref) ->
                val name = str(ref) ?: throw IllegalArgumentException("secretHeaders.$header must be a secret name")
                val fields = secret(name, project) ?: throw IllegalArgumentException("no secret '$name'")
                put(header, fields["value"] ?: fields.values.singleOrNull() ?: throw IllegalArgumentException("secret '$name' has no single value field"))
            }
        }
        val r = downloads.download(DownloadRequest(
            url = url, dest = dest, title = str(args["title"]).orEmpty(), headers = headers, secretHeaders = secrets,
            sha256 = str(args["sha256"]), progress = str(args["progressId"])?.let { handles[it] },
        ))
        return buildJsonObject {
            put("ok", r.ok)
            put("path", dest.absolutePath)
            put("bytes", r.bytes)
            r.sha256?.let { put("sha256", it) }
            put("attempts", r.attempts)
            put("status", r.status)
            r.error?.let { put("error", it) }
            put("retryable", r.retryable)
            put("cancelled", r.cancelled)
        }
    }

    private fun str(e: JsonElement?): String? = (e as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    private fun num(e: JsonElement?): Long? = (e as? JsonPrimitive)?.longOrNull

    companion object {
        const val START = "progress.start"
        const val UPDATE = "progress.update"
        const val DOWNLOAD = "http.download"
    }
}
