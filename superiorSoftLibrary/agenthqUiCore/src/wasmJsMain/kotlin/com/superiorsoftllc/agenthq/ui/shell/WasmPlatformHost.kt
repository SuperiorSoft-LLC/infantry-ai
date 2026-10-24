package com.superiorsoftllc.agenthq.ui.shell

/** Browser host: no files, no env, no clipboard images yet; every optional capability keeps its interface default. */
private class WasmPlatformHost : PlatformHost {
    override val defaultDaemonUrl: String = "ws://localhost:8080"
    override fun setting(key: String): String? = null
    override fun readTextFile(path: String): String? = null
}

actual fun defaultPlatformHost(): PlatformHost = WasmPlatformHost()
