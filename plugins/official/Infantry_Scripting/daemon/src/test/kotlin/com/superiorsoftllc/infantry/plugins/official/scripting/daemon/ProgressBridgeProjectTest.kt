// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.scripting.daemon

import com.superiorsoftllc.infantry.daemon.api.downloads.DownloadRequest
import com.superiorsoftllc.infantry.daemon.api.downloads.DownloadResult
import com.superiorsoftllc.infantry.daemon.api.downloads.Downloads
import com.superiorsoftllc.infantry.plugin.api.Progress
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** HTTP-DOWNLOAD-1: a session-less caller names its project with args.project; a session caller keeps its own. */
class ProgressBridgeProjectTest {
    private val cfg = mapOf("scripting.fetch.enabled" to "true", "scripting.fetch.allow" to "127.0.0.1")
    private var seen: DownloadRequest? = null
    private val downloads = object : Downloads {
        override fun download(request: DownloadRequest): DownloadResult { seen = request; return DownloadResult(ok = true, bytes = 1, status = 200) }
    }
    private val progress = Proxy.newProxyInstance(Progress::class.java.classLoader, arrayOf(Progress::class.java)) { _, _, _ -> null } as Progress
    private val bridge = ProgressBridge(progress, downloads, { cfg[it] }, { _, _ -> null }, { p, path -> "/state/$p/.infantry/$path" }, { listOf("p1", "p2") })
    private fun args(j: String): JsonObject = Json.parseToJsonElement(j).jsonObject
    private val q = "\""
    private val base = "\"url\":\"http://127.0.0.1:1/x\",\"dest\":\"a.bin\""

    @Test fun sessionLessCallerWithoutProjectGetsAnErrorThatSaysHowToPassOne() {
        val e = assertFailsWith<IllegalArgumentException> { bridge.download(args("{$base}"), null) }
        assertTrue("args.project" in e.message!!, e.message)
    }

    @Test fun sessionLessCallerPassesProject() {
        bridge.download(args("{$base,"+q+"project"+q+":"+q+"p2"+q+"}"), null)
        assertTrue(seen!!.dest.path.replace(java.io.File.separatorChar, '/').endsWith("/state/p2/.infantry/a.bin"), seen!!.dest.path)
    }

    @Test fun unknownProjectRefused() {
        assertFailsWith<IllegalArgumentException> { bridge.download(args("{$base,"+q+"project"+q+":"+q+"nope"+q+"}"), null) }
    }

    @Test fun sessionProjectWinsOverTheArg() {
        bridge.download(args("{$base,"+q+"project"+q+":"+q+"p2"+q+"}"), "p1")
        assertEquals("/state/p1/.infantry/a.bin", seen!!.dest.path.replace(java.io.File.separatorChar, '/'))
    }
}
