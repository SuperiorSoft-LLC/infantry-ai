package com.superiorsoftllc.infantry.db

import com.superiorsoftllc.infantry.protocol.RoleIds

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver

/**
 * ORIGIN-SCRUB-1a: rewrites every stored role id that [RoleIds.RETIRED] retired to its replacement, once per open.
 *
 * WHY ON OPEN AND NOT AS A NUMBERED .sqm: this is data, not schema, and it must also catch a row an older client
 * writes AFTER the upgrade (a daemon accepts a retired id through [RoleIds.normalize], but a second process on an
 * older build could still write one). Every statement is `WHERE <column> = <retired id>`, so a second run changes
 * nothing: idempotent by construction.
 *
 * Keyed tables (`role_bindings`, `role_traits`, one row per role) can hold BOTH ids when a row was written under
 * the new id before an old one was migrated: the new id's row wins, and the retired one is deleted.
 *
 * `decisions.author_role` is deliberately NOT rewritten: decisions are append-only (a trigger refuses any UPDATE),
 * and the author's role is a record of who wrote it then. A reader maps it through [RoleIds.normalize] if it must.
 *
 * [needed] is a read-only probe the caller runs first, so a clean file never takes the write lock on open; [apply]
 * runs in one transaction, so a crash never leaves a half-renamed file.
 */
object RoleIdMigration {
    // TODO(AR-4a): the `tickets.agent_id` statements belong in the board plugin's migrations (Infantry_Board, through api.storage).
    // Left here because this runs on EVERY open and catches rows an older client writes after the upgrade, and the board's
    // migration path is not yet an on-open data pass; moving it would stop healing those rows. Core tables stay here.


    /** True when any role-bearing column still holds a retired id. Read-only. */
    fun needed(driver: SqlDriver): Boolean {
        val ids = RoleIds.RETIRED.keys.joinToString(",") { "'$it'" } // compile-time constants, never user input
        val tickets = RoleIds.RETIRED.keys.joinToString(",") { "'role:$it'" }
        val sql = "SELECT EXISTS(SELECT 1 FROM agents WHERE role IN ($ids))" +
            " OR EXISTS(SELECT 1 FROM session_instances WHERE role IN ($ids))" +
            " OR EXISTS(SELECT 1 FROM tickets WHERE agent_id IN ($tickets))" +
            KEYED_TABLES.joinToString("") { " OR EXISTS(SELECT 1 FROM $it WHERE role_id IN ($ids))" }
        return driver.executeQuery(null, sql, { c ->
            QueryResult.Value(c.next().value && (c.getLong(0) ?: 0L) != 0L)
        }, 0).value
    }

    fun apply(driver: SqlDriver) {
        for ((old, new) in RoleIds.RETIRED) {
            driver.update("UPDATE agents SET role = ? WHERE role = ?", new, old)
            driver.update("UPDATE session_instances SET role = ? WHERE role = ?", new, old)
            driver.update("UPDATE tickets SET agent_id = ? WHERE agent_id = ?", "role:$new", "role:$old")
            for (table in KEYED_TABLES) {
                driver.update("UPDATE OR IGNORE $table SET role_id = ? WHERE role_id = ?", new, old)
                driver.update("DELETE FROM $table WHERE role_id = ?", old)
            }
        }
    }

    /** Tables with one row per role, `role_id` their primary key. Compile-time constants: never user input. */
    private val KEYED_TABLES = listOf("role_bindings", "role_traits")

    private fun SqlDriver.update(sql: String, vararg args: String) {
        execute(null, sql, args.size) { args.forEachIndexed { i, a -> bindString(i, a) } }
    }
}
