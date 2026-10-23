package com.superiorsoftllc.agenthq.cli

import kotlin.test.Test
import kotlin.test.assertEquals

/** The packaged CLI finds the installed daemon from AGENTHQ_INSTALL_PORT / AGENTHQ_INSTALL_STATE_DIR. */
class CliInstalledTargetTest {
    private fun env(vararg p: Pair<String, String>): (String) -> String? = p.toMap()::get

    @Test
    fun installPortIsUsedWhenNoAddressIsGiven() =
        assertEquals("ws://127.0.0.1:8799/ws", Cli.targetUrl(arrayOf("pods"), env("AGENTHQ_INSTALL_PORT" to "8799")))

    @Test
    fun stateDirAloneMeansTheInstalledDefaultPort() =
        assertEquals("ws://127.0.0.1:8797/ws", Cli.targetUrl(arrayOf("pods"), env("AGENTHQ_INSTALL_STATE_DIR" to "C:/x")))

    @Test
    fun ambientUrlAndTypedPortStillWin() {
        val e = env("AGENTHQ_INSTALL_PORT" to "8799", "AGENTHQ_URL" to "ws://127.0.0.1:5/ws")
        assertEquals("ws://127.0.0.1:5/ws", Cli.targetUrl(arrayOf("pods"), e))
        assertEquals("ws://127.0.0.1:6/ws", Cli.targetUrl(arrayOf("pods", "--port", "6"), e))
    }

    @Test
    fun devShellKeepsDevDefault() =
        assertEquals("ws://127.0.0.1:8787/ws", Cli.targetUrl(arrayOf("pods"), env()))
}
