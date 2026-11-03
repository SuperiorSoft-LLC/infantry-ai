// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon.api.storage

/**
 * DAEMON-STORAGE-1 (DaemonApi 1.15.0, additive): plugin-owned tables in the daemon's one SQLite file. Every table, index and
 * view a plugin names must start with the module's prefix (the module id lowercased, non-alphanumerics as `_`, plus `_`); an
 * OFFICIAL module may also name the tables it declares in module.json `daemon.storage.adopt`. Any other name is refused.
 * A community plugin needs the grant `storage`. Values: null, Long, Double, String, ByteArray (Int / Boolean bind as Long).
 */
interface PluginStorage {
    /** Runs [sql] once and records [version] as this module's schema version; a version already recorded (or higher) is a no-op. */
    fun migrate(version: Int, sql: List<String>)

    fun query(sql: String, args: List<Any?> = emptyList()): List<Map<String, Any?>>

    /** Returns the number of rows changed. */
    fun execute(sql: String, args: List<Any?> = emptyList()): Int

    /** [block] and its statements commit together; an exception rolls all of them back and is rethrown. Nests into the outer one. */
    fun <T> transaction(block: () -> T): T
}

/** The default: every call refuses. */
object NoPluginStorage : PluginStorage {
    private fun no(): Nothing = throw IllegalStateException("this daemon offers no plugin storage")
    override fun migrate(version: Int, sql: List<String>) = no()
    override fun query(sql: String, args: List<Any?>): List<Map<String, Any?>> = no()
    override fun execute(sql: String, args: List<Any?>): Int = no()
    override fun <T> transaction(block: () -> T): T = no()
}
