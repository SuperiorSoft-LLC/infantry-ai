package com.superiorsoftllc.agenthq.ui.voice.plugin

/**
 * A stand-in voice plugin, run by the tests as a real child JVM: `ok` reads a line and writes 3200 bytes of PCM,
 * `hang` never exits, `fail` exits 3, `empty` exits 0 having written nothing.
 */
object FakeTts {
    @JvmStatic
    fun main(args: Array<String>) {
        when (args.firstOrNull()) {
            "ok" -> { readLine(); System.out.write(ByteArray(3200) { 1 }); System.out.flush() }
            "hang" -> Thread.sleep(600_000)
            "fail" -> kotlin.system.exitProcess(3)
            else -> Unit
        }
    }
}
