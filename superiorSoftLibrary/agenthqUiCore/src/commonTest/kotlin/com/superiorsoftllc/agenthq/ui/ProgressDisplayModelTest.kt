package com.superiorsoftllc.agenthq.ui

import com.superiorsoftllc.agenthq.ui.model.ProgressTone
import com.superiorsoftllc.agenthq.ui.model.formatBytes
import com.superiorsoftllc.agenthq.ui.model.formatElapsed
import com.superiorsoftllc.agenthq.ui.model.progressDisplay
import com.superiorsoftllc.agenthq.ui.protocol.ProgressView
import com.superiorsoftllc.agenthq.ui.protocol.SessionRecord
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Contract 1.117.0: how a progress bar reads (tone, fill, headline, detail), without a window. */
class ProgressDisplayModelTest {
    private val min = 60_000L

    @Test fun `a fresh measured job runs, a quiet one turns stale after the threshold`() {
        val p = ProgressView(mode = "percent", percent = 62.0, label = "gate", startedAt = 0, updatedAt = 0)
        val fresh = progressDisplay(p, now = 4 * min, staleMin = 5)
        assertEquals(ProgressTone.Running, fresh.tone)
        assertEquals(0.62f, fresh.fraction)
        assertEquals("62%", fresh.headline)
        assertEquals("gate · 4m 00s", fresh.detail)
        val stale = progressDisplay(p, now = 6 * min, staleMin = 5)
        assertEquals(ProgressTone.Stale, stale.tone)
        assertTrue(stale.detail.endsWith("no update for 6 min"), stale.detail)
        assertEquals(ProgressTone.Running, progressDisplay(p, now = 6 * min, staleMin = 10).tone, "the setting moves the threshold")
        assertEquals(Palette.Accent, progressColor(stale.tone), "stale is amber")
    }

    @Test fun `done is green and full, failed is red`() {
        val done = progressDisplay(ProgressView(mode = "steps", state = "done", percent = 100.0, stepsTotal = 4, stepsDone = 4, updatedAt = 0), now = 60 * min)
        assertEquals(ProgressTone.Done, done.tone, "a finished job never turns stale")
        assertEquals(1f, done.fraction)
        assertEquals("100% · step 4/4 · done", done.headline)
        assertEquals(Palette.Ok, progressColor(done.tone))
        val failed = progressDisplay(ProgressView(mode = "count", state = "failed", percent = 30.0, done = 3, total = 10, label = "exit 1"), now = 0)
        assertEquals(ProgressTone.Failed, failed.tone)
        assertEquals("30% · 3 / 10 · failed", failed.headline)
        assertEquals("exit 1", failed.detail)
        assertEquals(Palette.Danger, progressColor(failed.tone))
        assertEquals(Palette.Info, progressColor(ProgressTone.Running))
    }

    @Test fun `steps headline names the running step`() {
        val d = progressDisplay(ProgressView(mode = "steps", percent = 40.0, stepsTotal = 5, stepsDone = 2, step = "unit tests"), now = 0)
        assertEquals("40% · step 3/5 unit tests", d.headline)
    }

    @Test fun `bytes text and speed`() {
        val d = progressDisplay(
            ProgressView(mode = "bytes", percent = 50.0, done = 512L shl 20, total = 1L shl 30, bytesPerSec = 2.5 * (1 shl 20), label = "jdk"),
            now = 65_000,
        )
        assertEquals("50% · 512.0 MB / 1.0 GB", d.headline)
        assertEquals("jdk · 2.5 MB/s · 1m 05s", d.detail)
        assertEquals("900 B", formatBytes(900))
        assertEquals("1.5 KB", formatBytes(1536))
        assertEquals("1h 03m", formatElapsed(3_780_000))
    }

    @Test fun `indeterminate shows a shimmer and its label`() {
        val d = progressDisplay(ProgressView(mode = "indeterminate", label = "waiting for CI"), now = 42_000)
        assertNull(d.fraction, "no number is implied")
        assertEquals("waiting for CI", d.headline)
        assertEquals("42s", d.detail)
        assertEquals("working", progressDisplay(ProgressView(mode = "indeterminate"), now = 0).headline)
    }

    @Test fun `the session record decodes progress and an older daemon's record has none`() {
        val json = Json { ignoreUnknownKeys = true }
        val base = """"id":"s","provider":"p","workingDir":".","spawnedAt":0,"lastHeartbeat":0,"state":"IDLE""""
        assertNull(json.decodeFromString(SessionRecord.serializer(), "{$base}").progress)
        val r = json.decodeFromString(SessionRecord.serializer(), """{$base,"progress":{"mode":"count","done":2,"total":4,"percent":50.0,"startedAt":1,"updatedAt":2,"future":1}}""")
        assertEquals(50.0, r.progress!!.percent)
        assertEquals("running", r.progress!!.state)
    }
}
