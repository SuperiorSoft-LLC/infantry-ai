package com.superiorsoftllc.agenthq.backlog

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.superiorsoftllc.agenthq.protocol.TicketType

/**
 * ORIGIN-SCRUB-1b: rewrites every stored `tickets.ticket_type` holding a [TicketType.RETIRED] spelling to its
 * replacement, once per open. Same shape as `RoleIdMigration` (ORIGIN-SCRUB-1a), for the same reason: this is data,
 * not schema, and it must also catch a row an older build writes after the upgrade.
 *
 * Every statement is `WHERE ticket_type = <retired>`, so a second run changes nothing: idempotent by construction.
 * [needed] is a read-only probe the caller runs first, so a clean file never takes the write lock; the caller runs
 * [apply] in one transaction.
 */
object TicketTypeMigration {

    /** True when any ticket still holds a retired type. Read-only. */
    fun needed(driver: SqlDriver): Boolean {
        val retired = TicketType.RETIRED.keys.joinToString(",") { "'$it'" } // compile-time constants, never user input
        return driver.executeQuery(null, "SELECT EXISTS(SELECT 1 FROM tickets WHERE ticket_type IN ($retired))", { c ->
            QueryResult.Value(c.next().value && (c.getLong(0) ?: 0L) != 0L)
        }, 0).value
    }

    fun apply(driver: SqlDriver) {
        for ((old, new) in TicketType.RETIRED) {
            driver.execute(null, "UPDATE tickets SET ticket_type = ? WHERE ticket_type = ?", 2) {
                bindString(0, new)
                bindString(1, old)
            }
        }
    }
}
