// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.jobfeed.daemon

import com.superiorsoftllc.infantry.daemon.api.DaemonApi
import com.superiorsoftllc.infantry.daemon.api.DaemonPlugin
import com.superiorsoftllc.infantry.daemon.api.HttpCall
import com.superiorsoftllc.infantry.daemon.api.HttpReply
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** JOBFEED-1: the two POST routes of the Job Feed box (the UI half mirrors them; nothing else knows them). */
object JobFeedWire {
    const val JOBS = "jobs"
    const val READ = "read"
}

/** JOBFEED-1: answers the Job Feed box's two read routes from [JobFeedService]. Starts nothing: no timer, no thread. */
class JobFeedDaemonPlugin : DaemonPlugin {
    override fun start(api: DaemonApi) {
        val service = JobFeedService(
            api.sessions,
            minIntervalMs = { api.config("jobfeed.minIntervalMs")?.toLongOrNull()?.coerceAtLeast(0L) ?: DEFAULT_MIN_INTERVAL_MS },
            maxLineChars = { api.config("jobfeed.maxLineChars")?.toIntOrNull()?.coerceAtLeast(0) ?: DEFAULT_MAX_LINE_CHARS },
            stripAnsi = { api.config("jobfeed.stripAnsi")?.let { it != "false" } ?: true },
            maxTracked = { api.config("jobfeed.maxTracked")?.toIntOrNull() ?: DEFAULT_MAX_TRACKED },
        )
        api.routes.handle("POST", JobFeedWire.JOBS) { f: HttpCall ->
            val sid = field(f, "sessionId")
            val body = buildJsonObject {
                put("sessionId", sid.orEmpty())
                put("jobs", buildJsonArray {
                    if (sid != null) service.jobs(sid).forEach { j ->
                        add(buildJsonObject {
                            put("id", j.id); put("label", j.label); put("startedAt", j.startedAt); put("running", j.running)
                            j.exitCode?.let { put("exitCode", it) }
                        })
                    }
                })
            }
            HttpReply(200, body.toString(), "application/json")
        }
        api.routes.handle("POST", JobFeedWire.READ) { f: HttpCall ->
            val sid = field(f, "sessionId")
            val job = field(f, "jobId")
            val from = obj(f)?.get("from")?.let { (it as? JsonPrimitive)?.longOrNull } ?: -1L
            val max = obj(f)?.get("maxBytes")?.let { (it as? JsonPrimitive)?.longOrNull }?.toInt() ?: DEFAULT_READ_BYTES
            val r = if (sid != null && job != null) service.read(sid, job, from, max) else JobFeedService.Read(null, "", false)
            val c = r.chunk
            val body = buildJsonObject {
                put("jobId", job.orEmpty())
                put("text", r.text)
                put("throttled", r.throttled)
                put("known", c != null)
                if (c != null) {
                    put("next", c.nextOffset); put("size", c.size); put("reset", c.reset)
                    put("label", c.job.label); put("startedAt", c.job.startedAt); put("running", c.job.running)
                    c.job.exitCode?.let { put("exitCode", it) }
                } else put("next", from)
            }
            HttpReply(200, body.toString(), "application/json")
        }
    }

    private fun obj(f: HttpCall): JsonObject? = runCatching { Json.parseToJsonElement(f.body) as? JsonObject }.getOrNull()
    private fun field(f: HttpCall, k: String): String? = (obj(f)?.get(k) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    companion object {
        /** The defaults the module.json fields carry too; here only so a missing config value still behaves. */
        const val DEFAULT_MIN_INTERVAL_MS = 200L
        const val DEFAULT_MAX_LINE_CHARS = 300
        const val DEFAULT_MAX_TRACKED = 200
        const val DEFAULT_READ_BYTES = 16384
    }
}
