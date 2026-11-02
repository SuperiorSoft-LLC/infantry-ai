package com.superiorsoftllc.agenthq.stats

import java.io.File
import java.sql.DriverManager
import java.util.Properties

/**
 * HARNESS H8b-1: the ONE read-only sqlite reader of the daemon. A harness's own session store (opencode's `opencode.db`) is read through it:
 * by the core code in place, and by a plugin through `HarnessHost.queryDb` (API 2.11.0), so a plugin jar carries no database driver.
 * Opened read-only with a busy timeout; every failure (missing file, bad SQL, locked) answers null, never throws.
 */
object ReadOnlyDb {
    private const val SQLITE_OPEN_READONLY = 0x00000001
    private const val BUSY_TIMEOUT_MS = 2_000

    fun query(file: File, sql: String, params: List<String> = emptyList()): List<Map<String, Any?>>? {
        if (!file.isFile) return null
        val props = Properties().apply {
            setProperty("open_mode", SQLITE_OPEN_READONLY.toString())
            setProperty("busy_timeout", BUSY_TIMEOUT_MS.toString())
        }
        return try {
            DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}", props).use { c ->
                c.prepareStatement(sql).use { st ->
                    params.forEachIndexed { i, v -> st.setString(i + 1, v) }
                    st.executeQuery().use { rs ->
                        val names = (1..rs.metaData.columnCount).map { rs.metaData.getColumnLabel(it) }
                        buildList { while (rs.next()) add(names.mapIndexed { i, n -> n to rs.getObject(i + 1) }.toMap()) }
                    }
                }
            }
        } catch (e: Exception) {
            null
        }
    }
}
