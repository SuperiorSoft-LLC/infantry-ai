// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.shell

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Portable shell behaviour: how a host's settings become the daemon URL + token. No platform API is touched. */
class ShellConfigTest {
    private class FakeHost(
        private val settings: Map<String, String> = emptyMap(),
        private val files: Map<String, String> = emptyMap(),
        override val defaultDaemonUrl: String = "ws://default/ws",
    ) : PlatformHost {
        override fun setting(key: String): String? = settings[key]
        override fun readTextFile(path: String): String? = files[path]
    }

    @Test fun usesHostDefaultUrlWhenNothingConfigured() {
        val cfg = resolveShellConfig(FakeHost())
        assertEquals("ws://default/ws", cfg.url)
        assertNull(cfg.token)
    }

    @Test fun explicitUrlWinsOverDefault() {
        val cfg = resolveShellConfig(FakeHost(settings = mapOf(ShellKeys.WS_URL to "ws://daemon:9/ws")))
        assertEquals("ws://daemon:9/ws", cfg.url)
    }

    @Test fun blankUrlFallsBackToDefault() {
        assertEquals("ws://default/ws", resolveShellConfig(FakeHost(settings = mapOf(ShellKeys.WS_URL to "  "))).url)
    }

    @Test fun tokenFileIsReadAndTrimmed() {
        val cfg = resolveShellConfig(FakeHost(settings = mapOf(ShellKeys.TOKEN_FILE to "/t"), files = mapOf("/t" to " s3cret\n")))
        assertEquals("s3cret", cfg.token)
    }

    @Test fun tokenFileBeatsInlineToken() {
        val host = FakeHost(settings = mapOf(ShellKeys.TOKEN_FILE to "/t", ShellKeys.TOKEN to "inline"), files = mapOf("/t" to "fromfile"))
        assertEquals("fromfile", resolveShellConfig(host).token)
    }

    @Test fun unreadableTokenFileFallsBackToInlineToken() {
        val host = FakeHost(settings = mapOf(ShellKeys.TOKEN_FILE to "/missing", ShellKeys.TOKEN to "inline"))
        assertEquals("inline", resolveShellConfig(host).token)
    }

    @Test fun blankTokensAreNull() {
        val host = FakeHost(settings = mapOf(ShellKeys.TOKEN_FILE to " ", ShellKeys.TOKEN to ""))
        assertNull(resolveShellConfig(host).token)
    }

    @Test fun layeredSettingsTakeFirstNonBlankSourceInOrder() {
        val first = mapOf("A" to "", "B" to "b1")
        val second = mapOf("A" to "a2", "B" to "b2", "C" to "c2")
        val layered = LayeredSettings({ first[it] }, { second[it] })
        assertEquals("a2", layered["A"])
        assertEquals("b1", layered["B"])
        assertEquals("c2", layered["C"])
        assertNull(layered["D"])
    }
}
