// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon.plugins

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.driver.jdbc.JdbcCursor
import com.superiorsoftllc.infantry.daemon.api.storage.PluginStorage
import com.superiorsoftllc.infantry.db.InfantryDatabaseHandle
import com.superiorsoftllc.infantry.db.TrackedTransacter

/**
 * DAEMON-STORAGE-1: [PluginStorage] over the daemon's one SQLite connection (same driver, same write tracking as the store).
 * [adopt] = the core tables the module may name beyond its prefix (empty for a community module); [may] answers whether the
 * module holds the grant `storage` (official / core: always).
 */
class LocalPluginStorage(
    private val handle: InfantryDatabaseHandle,
    private val moduleId: String,
    adopt: List<String> = emptyList(),
    private val may: () -> Boolean = { true },
) : PluginStorage {
    private val prefix = prefixOf(moduleId)
    private val adopted = adopt.map { it.lowercase() }.toSet()
    private val tx = TrackedTransacter(handle.database, handle.writes)

    override fun migrate(version: Int, sql: List<String>) {
        granted()
        require(version > 0) { "schema version must be positive" }
        sql.forEach { checkSql(it) }
        tx.transactionWithResult {
            handle.driver.execute(null, "CREATE TABLE IF NOT EXISTS $VERSIONS (module TEXT PRIMARY KEY, version INTEGER NOT NULL)", 0)
            val current = rows("SELECT version FROM $VERSIONS WHERE module = ?", listOf(moduleId)).firstOrNull()?.get("version").let { (it as? Number)?.toLong() ?: 0L }
            if (current < version) {
                sql.forEach { handle.driver.execute(null, it, 0) }
                bindExec(
                    "INSERT INTO $VERSIONS (module, version) VALUES (?, ?) ON CONFLICT(module) DO UPDATE SET version = excluded.version",
                    listOf(moduleId, version.toLong()),
                )
            }
        }
    }

    override fun query(sql: String, args: List<Any?>): List<Map<String, Any?>> {
        granted()
        checkSql(sql)
        return rows(sql, args)
    }

    override fun execute(sql: String, args: List<Any?>): Int {
        granted()
        checkSql(sql)
        return bindExec(sql, args).toInt()
    }

    override fun <T> transaction(block: () -> T): T {
        granted()
        return tx.transactionWithResult { block() }
    }

    private fun granted() = check(may()) { "missing grant '$GRANT' in modules.json" }

    private fun checkSql(sql: String) {
        tablesRefused(sql, prefix, adopted)?.let { throw IllegalArgumentException(it) }
    }

    private fun bindExec(sql: String, args: List<Any?>): Long =
        handle.driver.execute(null, sql, args.size) { args.forEachIndexed { i, a -> bind(i, a) } }.value

    private fun rows(sql: String, args: List<Any?>): List<Map<String, Any?>> =
        handle.driver.executeQuery(null, sql, { c ->
            val rs = (c as? JdbcCursor)?.resultSet ?: error("plugin storage needs a JDBC cursor")
            val meta = rs.metaData
            val names = (1..meta.columnCount).map { meta.getColumnLabel(it) }
            val out = ArrayList<Map<String, Any?>>()
            while (c.next().value) {
                out += names.indices.associate { i -> names[i] to rs.getObject(i + 1) }
            }
            QueryResult.Value(out)
        }, args.size) { args.forEachIndexed { i, a -> bind(i, a) } }.value

    private fun SqlPreparedStatement.bind(i: Int, a: Any?) {
        when (a) {
            null -> bindString(i, null)
            is Long -> bindLong(i, a)
            is Int -> bindLong(i, a.toLong())
            is Boolean -> bindLong(i, if (a) 1L else 0L)
            is Double -> bindDouble(i, a)
            is Float -> bindDouble(i, a.toDouble())
            is String -> bindString(i, a)
            is ByteArray -> bindBytes(i, a)
            else -> throw IllegalArgumentException("unsupported argument type ${a::class.simpleName}")
        }
    }

    companion object {
        const val GRANT = "storage"

        /** The host's own table (schema version per module); a plugin can never name it (no prefix, not adoptable). */
        const val VERSIONS = "plugin_storage_versions"

        fun prefixOf(moduleId: String): String = moduleId.lowercase().replace(Regex("[^a-z0-9]"), "_") + "_"

        private val NAME_KEYWORDS = setOf("from", "into", "update", "join", "table", "index", "view", "trigger")
        private val SKIP = setOf("if", "not", "exists", "only", "unique")
        private val DENIED = setOf("attach", "detach", "pragma", "vacuum", "load_extension")

        /** Why [sql] may not run for a module with [prefix] and [adopted] tables; null = it may. A tokenizer, not a SQL parser. */
        fun tablesRefused(sql: String, prefix: String, adopted: Set<String>): String? {
            val t = tokens(sql) ?: return "unterminated string or comment"
            val semi = t.indexOf(";")
            if (semi >= 0 && semi != t.lastIndex) return "one statement per call"
            t.firstOrNull { it.lowercase() in DENIED }?.let { return "'$it' is not allowed in plugin storage" }
            val createIndex = t.isNotEmpty() && t[0].equals("create", true) && t.take(4).any { it.equals("index", true) }
            for (i in t.indices) {
                val kw = t[i].lowercase()
                if (!(kw in NAME_KEYWORDS || (kw == "on" && createIndex))) continue
                if (kw == "update" && i > 0 && t[i - 1].equals("for", true)) continue
                var j = i + 1
                while (true) {
                    while (j < t.size && t[j].lowercase() in SKIP) j++
                    if (j >= t.size || t[j] == "(" || t[j] == ";") break
                    val raw = t[j]
                    val name = raw.trim('"', '`', '[', ']').lowercase()
                    if (!(name.startsWith(prefix) || name in adopted) || name.contains('.')) {
                        return "table '$raw' is not owned by this module (prefix '$prefix')"
                    }
                    if (kw == "from" || kw == "join") {
                        if (j + 1 < t.size && t[j + 1] == "(") return "'$raw(' is a function call, not a table"
                    }
                    // FROM a [AS] x, b: the name after the comma is a table too.
                    var k = j + 1
                    if (kw == "from") {
                        if (k < t.size && t[k].equals("as", true)) k++
                        if (k + 1 < t.size && t[k] != "," && t[k + 1] == ",") k++
                    }
                    if (kw == "from" && k < t.size && t[k] == ",") { j = k + 1; continue }
                    break
                }
            }
            return null
        }

        /** Words, quoted names and punctuation; string literals and comments are dropped. Null when one never closes. */
        private fun tokens(sql: String): List<String>? {
            val out = ArrayList<String>()
            var i = 0
            while (i < sql.length) {
                val c = sql[i]
                when {
                    c.isWhitespace() -> i++
                    sql.startsWith("--", i) -> i = sql.indexOf('\n', i).let { if (it < 0) sql.length else it + 1 }
                    sql.startsWith("/*", i) -> {
                        val e = sql.indexOf("*/", i + 2)
                        if (e < 0) return null
                        i = e + 2
                    }
                    c == '\'' -> {
                        i++
                        while (true) {
                            if (i >= sql.length) return null
                            if (sql[i] == '\'') {
                                if (sql.startsWith("''", i)) i += 2 else { i++; break }
                            } else i++
                        }
                    }
                    c == '"' || c == '`' || c == '[' -> {
                        val close = if (c == '[') ']' else c
                        val e = sql.indexOf(close, i + 1)
                        if (e < 0) return null
                        out += sql.substring(i, e + 1)
                        i = e + 1
                    }
                    c.isLetterOrDigit() || c == '_' || c == '.' -> {
                        val s = i
                        while (i < sql.length && (sql[i].isLetterOrDigit() || sql[i] == '_' || sql[i] == '.')) i++
                        out += sql.substring(s, i)
                    }
                    else -> { out += c.toString(); i++ }
                }
            }
            return out
        }
    }
}
