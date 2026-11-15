// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * HTTP-SEAM-1: one outgoing HTTP client. A daemon plugin calls out through `DaemonApi.http` (core: `CoreHttp`), a file download
 * goes through `Downloads`. Main sources may not open their own client:
 * `java.net.http.HttpClient`, `HttpClient.newHttpClient`, `HttpURLConnection`, `io.ktor.client.HttpClient(`.
 *
 * The allow-list holds the two seam files and every site that existed when the seam landed; each is migrated in HTTP-SEAM-2.
 * Remove a line when its file moves onto the seam. Never add one for new code: use the seam.
 */
class HttpSeamGuardTest {
    private val roots = listOf(File(".."), File("../../plugins"), File("../../samples"))

    private val rule = Regex("""java\.net\.http\.HttpClient|HttpClient\.newHttpClient|HttpURLConnection|io\.ktor\.client\.HttpClient\(""")

    private val seamFiles = mapOf(
        "infantryCore/src/main/kotlin/com/superiorsoftllc/infantry/http/CoreHttp.kt" to "THE client behind DaemonApi.http",
        "infantryCore/src/main/kotlin/com/superiorsoftllc/infantry/downloads/HttpDownloadTransport.kt" to "THE core download transport (Downloads)",
    )

    private val migrateLater = "migrate in HTTP-SEAM-2"

    /** Path suffix -> why it may still open its own client. */
    private val allowed: Map<String, String> = seamFiles + listOf(
        "infantry/src/main/kotlin/com/superiorsoftllc/infantry/cli/CliMain.kt",
        "infantry/src/main/kotlin/com/superiorsoftllc/infantry/client/ClientMain.kt",
        "infantry/src/main/kotlin/com/superiorsoftllc/infantry/daemon/HealthGate.kt",
        "infantry/src/main/kotlin/com/superiorsoftllc/infantry/daemon/StallProbe.kt",
        "infantry/src/main/kotlin/com/superiorsoftllc/infantry/daemon/LoopbackFrames.kt",
        "infantry/src/main/kotlin/com/superiorsoftllc/infantry/daemon/plugins/LoopbackFrames.kt",
        "infantry/src/main/kotlin/com/superiorsoftllc/infantry/daemon/plugins/manager/PluginManager.kt",
        "infantryCore/src/main/kotlin/com/superiorsoftllc/infantry/usage/Limits.kt",
        "infantryUi/src/main/kotlin/com/superiorsoftllc/infantry/ui/WindowActivation.kt",
        "infantryUiCore/src/jvmMain/kotlin/com/superiorsoftllc/infantry/ui/plugin/JvmEndpointAccess.kt",
        "infantryUiCore/src/jvmMain/kotlin/com/superiorsoftllc/infantry/ui/voice/plugin/VoicePluginStore.kt",
        "official/Infantry_Claude/harness/src/main/kotlin/com/superiorsoftllc/infantry/plugins/official/claude/harness/ClaudeStatsUsage.kt",
        "official/Infantry_OpenCode/harness/src/main/kotlin/com/superiorsoftllc/infantry/plugins/official/opencode/harness/OpenCodeServeSession.kt",
        "official/Infantry_RemoteRunner/daemon/src/main/kotlin/com/superiorsoftllc/infantry/plugins/official/remoterunner/daemon/LinkClient.kt",
        "official/Infantry_Updater/androidSrc/AndroidSelfUpdate.kt",
        "official/Infantry_Updater/daemon/src/main/kotlin/com/superiorsoftllc/infantry/plugins/official/updater/GitHubReleases.kt",
        "official/Infantry_Updater/daemon/src/main/kotlin/com/superiorsoftllc/infantry/plugins/official/updater/ServerUpdateCli.kt",
    ).associateWith { migrateLater }

    private fun isMainSource(f: File): Boolean {
        val p = f.invariantSeparatorsPath
        return f.extension == "kt" && listOf("/build/", "/src/test/", "/jvmTest/", "/androidTest/", "/headlessSmoke/", "/.gradle/").none { it in p }
    }

    private fun matches(): Map<String, List<String>> {
        val out = sortedMapOf<String, List<String>>()
        for (root in roots) {
            if (!root.isDirectory) continue
            root.walkTopDown().onEnter { it.name != "build" && it.name != ".gradle" && it.name != "node_modules" }
                .filter { it.isFile && isMainSource(it) }.forEach { f ->
                    val hits = f.readLines().withIndex().filter { (_, l) -> rule.containsMatchIn(l) && "// http-seam-ok" !in l }.map { "${it.index + 1}: ${it.value.trim()}" }
                    if (hits.isNotEmpty()) out[f.canonicalPath.replace('\\', '/')] = hits
                }
        }
        return out
    }

    @Test fun `no main source opens its own HTTP client outside the seam and the commented allow-list`() {
        val found = matches()
        assertTrue(found.isNotEmpty(), "the scan found nothing: the roots are wrong, the guard would pass for the wrong reason")
        val offenders = found.filterKeys { path -> allowed.keys.none { path.endsWith(it) } }
        if (offenders.isNotEmpty()) {
            fail("Main code opens its own HTTP client. Use DaemonApi.http (plugins) or Downloads (files); do not add an allow-list line.\n" +
                offenders.entries.joinToString("\n") { "  ${it.key}\n    " + it.value.take(3).joinToString("\n    ") })
        }
    }

    @Test fun `every allow-list line still names a file that uses a client`() {
        val found = matches().keys
        val stale = allowed.keys.filter { suffix -> found.none { it.endsWith(suffix) } }
        assertTrue(stale.isEmpty(), "These allow-list lines no longer match (the file moved onto the seam, or was renamed): remove them.\n" + stale.joinToString("\n"))
    }
}
