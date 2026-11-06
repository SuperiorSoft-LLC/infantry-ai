// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * CONTRIB-1: Configuration > About's links to the source repository, built from the daemon's `urls.sourceRepo`
 * (env > setting > default, DOMAIN-ENV-1). Nothing here names a repository: a blank or missing value means no links.
 * The issue form is prefilled with build and platform facts only: no user data, paths or tokens.
 */
data class SourceLinks(val contribute: String, val reportProblem: String, val repo: String) {
    companion object {
        const val KEY = "urls.sourceRepo"
        const val SITE_KEY = "urls.site"

        /** ABOUT-SITE-1: the product website (`urls.site`) from a `settings` frame; null when absent, blank or not http(s). */
        fun siteFromSettingsFrame(data: String): String? {
            val obj = runCatching { Json.parseToJsonElement(data) as? JsonObject }.getOrNull() ?: return null
            val v = (obj[SITE_KEY] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim() ?: return null
            return v.takeIf { it.startsWith("https://") || it.startsWith("http://") }
        }

        /** The repo's `urls.sourceRepo` from a `settings` frame; null when the frame does not carry it. */
        fun repoFromSettingsFrame(data: String): String? {
            val obj = runCatching { Json.parseToJsonElement(data) as? JsonObject }.getOrNull() ?: return null
            val v = obj[KEY] as? JsonPrimitive ?: return null
            return if (v.isString) v.content.trim() else null
        }

        /** The three links for [repo], or null (the buttons hide) when it is blank or not http(s). */
        fun of(repo: String?, facts: ReportFacts): SourceLinks? {
            val base = repo?.trim()?.trimEnd('/')?.takeIf { it.startsWith("https://") || it.startsWith("http://") } ?: return null
            return SourceLinks(
                contribute = "$base/blob/main/CONTRIBUTING.md",
                reportProblem = "$base/issues/new?title=${encode("Problem: ")}&body=${encode(facts.issueBody())}",
                repo = base,
            )
        }

        /** Percent-encodes everything outside the URL unreserved set (UTF-8). */
        fun encode(s: String): String = buildString {
            for (b in s.encodeToByteArray()) {
                val c = b.toInt() and 0xff
                val ch = c.toChar()
                if (ch.isLetterOrDigit() && c < 0x80 || ch in "-_.~") append(ch)
                else append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 0xf])
            }
        }
    }
}

/** CONTRIB-1: the facts a problem report carries. Each is a version or a platform name, never user data. */
data class ReportFacts(val appBuild: String?, val daemonBuild: String?, val os: String?, val harnessMode: String?) {
    fun issueBody(): String = """
        |**What happened**
        |
        |
        |**What you expected**
        |
        |
        |**Steps to reproduce**
        |
        |
        |---
        |- App build: ${appBuild ?: "not stamped"}
        |- Daemon build: ${daemonBuild ?: "unknown"}
        |- OS: ${os ?: "unknown"}
        |- Harness mode: ${harnessMode ?: "unknown"}
    """.trimMargin()
}
