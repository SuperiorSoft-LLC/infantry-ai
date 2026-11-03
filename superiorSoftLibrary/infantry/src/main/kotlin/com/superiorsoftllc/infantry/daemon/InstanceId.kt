package com.superiorsoftllc.infantry.daemon

import java.io.File
import java.util.UUID

/**
 * BUG-REAP-OWNER: the identity of one daemon state dir. It is created once and then read back on every restart of the
 * same dir, so a kept process recorded by a previous run still names this dir's owner. Copying only `sessions.json`
 * (or the db) into another dir does not carry it: that dir mints its own id, and the copied entries stop matching.
 */
object InstanceId {
    const val FILE_NAME = "instance.id"

    fun loadOrCreate(stateDir: File): String {
        val file = File(stateDir, FILE_NAME)
        runCatching { file.readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
        val fresh = UUID.randomUUID().toString()
        stateDir.mkdirs()
        file.writeText(fresh)
        return fresh
    }
}
