// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.updater

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * UPDATE-UX-1: the end-to-end check against the REAL public release (network, ~160 MB), through the window's own path
 * ([UpdateService]: check, Update, background download with progress, verify, Ready). Off unless
 * `INFANTRY_UPDATE_LIVE=<the version expected, e.g. 1.0.7>` is set, so the normal test run never touches GitHub.
 * It never installs: the handoff is a recorder and "Restart now" is never pressed.
 */
class LiveReleaseDownloadTest {
    @Test
    fun `the real release downloads in the background and verifies`() = runBlocking<Unit> {
        val want = System.getenv("INFANTRY_UPDATE_LIVE")?.trim()?.takeIf { it.isNotEmpty() } ?: return@runBlocking
        val repo = System.getenv("INFANTRY_UPDATE_LIVE_REPO") ?: "SuperiorSoft-LLC/infantry-ai"
        val scope = CoroutineScope(SupervisorJob())
        var handoffs = 0
        val s = UpdateService(UpdateSettings.Enabled(UpdateConfig(repo, SemVer.parse("1.0.5")!!)), scope,
            handoff = { handoffs++ }, exit = { error("never exits in this test") }, log = { println("[infantry-update] $it") })
        s.check(manual = true)!!.join()
        val available = assertIs<UpdateState.Available>(s.state.value, "${s.state.value}")
        assertEquals(SemVer.parse(want), available.update.latest)
        val started = System.currentTimeMillis()
        val job = s.update()!!
        val seen = sortedSetOf<Int>()
        while (job.isActive) {
            (s.state.value as? UpdateState.Downloading)?.percent?.let { seen += it }
            kotlinx.coroutines.delay(200)
        }
        val ready = assertIs<UpdateState.Ready>(s.state.value, "${s.state.value}")
        println("[live] ready after ${(System.currentTimeMillis() - started) / 1000}s: ${ready.installer.file} sha256=${ready.installer.sha256}; percents seen ${seen.size}")
        assertTrue(ready.installer.checksumVerified)
        assertEquals(ready.update.installer.size, ready.installer.file.length())
        assertTrue(seen.size > 10, "the card saw the percentage move: $seen")
        assertEquals(0, handoffs, "nothing installed")
        ready.installer.file.parentFile.deleteRecursively()
        scope.cancel()
    }
}
