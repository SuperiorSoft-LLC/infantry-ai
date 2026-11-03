package com.superiorsoftllc.infantry.cli

import kotlin.test.Test
import kotlin.test.assertEquals

/** The packaged CLI finds the installed daemon from INFANTRY_INSTALL_PORT / INFANTRY_INSTALL_STATE_DIR. */
class CliInstalledTargetTest {
    private fun env(vararg p: Pair<String, String>): (String) -> String? = p.toMap()::get

    @Test
    fun installPortIsUsedWhenNoAddressIsGiven() =
        assertEquals("ws://127.0.0.1:8799/ws", Cli.targetUrl(arrayOf("pods"), env("INFANTRY_INSTALL_PORT" to "8799")))

    @Test
    fun stateDirAloneMeansTheInstalledDefaultPort() =
        assertEquals("ws://127.0.0.1:8797/ws", Cli.targetUrl(arrayOf("pods"), env("INFANTRY_INSTALL_STATE_DIR" to "C:/x")))

    @Test
    fun ambientUrlAndTypedPortStillWin() {
        val e = env("INFANTRY_INSTALL_PORT" to "8799", "INFANTRY_URL" to "ws://127.0.0.1:5/ws")
        assertEquals("ws://127.0.0.1:5/ws", Cli.targetUrl(arrayOf("pods"), e))
        assertEquals("ws://127.0.0.1:6/ws", Cli.targetUrl(arrayOf("pods", "--port", "6"), e))
    }

    @Test
    fun devShellKeepsDevDefault() =
        assertEquals("ws://127.0.0.1:8787/ws", Cli.targetUrl(arrayOf("pods"), env()))
}
