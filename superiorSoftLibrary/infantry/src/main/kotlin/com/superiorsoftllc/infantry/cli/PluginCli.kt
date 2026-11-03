// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.cli

import com.superiorsoftllc.infantry.daemon.plugins.manager.Catalog
import com.superiorsoftllc.infantry.daemon.plugins.manager.CatalogSource
import com.superiorsoftllc.infantry.daemon.plugins.manager.Inspection
import com.superiorsoftllc.infantry.daemon.plugins.manager.ManagerJson
import com.superiorsoftllc.infantry.daemon.plugins.manager.ManagerResult
import com.superiorsoftllc.infantry.daemon.plugins.manager.PluginManager
import com.superiorsoftllc.infantry.plugin.host.PluginFolders
import com.superiorsoftllc.infantry.session.stateDir
import java.io.File

/**
 * PLUGIN-MANAGER-1: `infantry plugin ...` manages the plugins root of THIS host (the same files the daemon and the app read),
 * so it contacts no daemon and adds no wire frame. A change to a daemon or harness half takes effect at the next daemon start.
 * An install without `--grant` prints what the plugin asks for and stops (no prompt): consent is always explicit.
 */
internal object PluginCli {
    const val USAGE = """usage: infantry plugin <command>
  list                          installed and bundled plugins (class, tier, version, enabled)
  search [text] [--tag t]       plugins in the configured catalogs
  show <ref>                    check a plugin and print its class, signer, licence and requested grants (installs nothing)
  install <ref> [--grant g]... [--grant-all-requested] [--no-grants] [--disabled]
  enable <id> | disable <id>
  grants <id> [--grant g]... [--grant-all-requested]
  updates                       newer versions of what the manager installed
  update <id>|--all [--grant g]... [--grant-all-requested] [--from <ref>]
  rollback <id> | remove <id> [--purge] | status <id>
  sources list | add <id> (--url U | --dir D) [--class official|community|custom] | remove <id> | refresh [id]
  catalog validate <catalog.json> [--assets dir]
<ref> = [source:]id[@version] from a catalog, a .ihq file, a plugin folder, or a URL (the last three are CUSTOM plugins)."""

    fun run(args: List<String>, out: Appendable, err: Appendable, getenv: (String) -> String?): Int {
        val dir = stateDir { getenv(it) ?: com.superiorsoftllc.infantry.session.infantrySetting(it) }
        val root = PluginFolders.root(dir, { getenv(it) })
        return run(args, out, err, PluginManager(root, dir, PluginFolders.bundledRoot({ getenv(it) })))
    }

    fun run(args: List<String>, out: Appendable, err: Appendable, m: PluginManager): Int {
        val grants = mutableListOf<String>()
        var all = false
        var none = false
        var disabled = false
        var purge = false
        var from: String? = null
        var assets: String? = null
        var cls: String? = null
        var url: String? = null
        var dir: String? = null
        val tags = mutableSetOf<String>()
        val rest = mutableListOf<String>()
        var i = 0
        fun value(flag: String): String? = args.getOrNull(++i).also { if (it == null) err.appendLine("error: $flag needs a value") }
        while (i < args.size) {
            when (val a = args[i]) {
                "--grant" -> grants += value(a) ?: return 2
                "--grant-all-requested" -> all = true
                "--no-grants" -> none = true
                "--disabled" -> disabled = true
                "--purge" -> purge = true
                "--all" -> rest += a
                "--from" -> from = value(a) ?: return 2
                "--assets" -> assets = value(a) ?: return 2
                "--class" -> cls = value(a) ?: return 2
                "--url" -> url = value(a) ?: return 2
                "--dir" -> dir = value(a) ?: return 2
                "--tag" -> tags += value(a) ?: return 2
                else -> rest += a
            }
            i++
        }
        fun usage(msg: String): Int { err.appendLine("error: $msg"); err.appendLine(USAGE); return 2 }
        fun show(r: ManagerResult): Int {
            if (r.ok) { out.appendLine(r.message + if (r.restartNeeded) " (takes effect at the next daemon start)" else ""); return 0 }
            err.appendLine("refused [${r.code}]: ${r.message}")
            if (r.requested.isNotEmpty()) err.appendLine("to accept: --grant-all-requested, or --grant <g> for each of: ${r.requested.joinToString(" ")}")
            return 1
        }
        fun consent(requested: List<String>?): Set<String>? = when {
            none -> emptySet()
            all -> requested?.toSet() ?: emptySet()
            grants.isNotEmpty() -> grants.toSet()
            else -> null
        }
        val sub = rest.firstOrNull() ?: return usage("no plugin command given")
        val arg = rest.getOrNull(1)
        when (sub) {
            "list" -> {
                val rows = m.list()
                if (rows.isEmpty()) out.appendLine("no plugins")
                rows.forEach { out.appendLine("${it.id}  ${it.version}  ${it.cls.label}${if (it.bundled) "/bundled" else ""}  ${it.tier.name.lowercase()}  ${if (it.enabled) "enabled" else "disabled"}  ${it.source ?: "unlisted"}") }
                return 0
            }
            "search" -> {
                val hits = m.search(arg.orEmpty(), tags)
                if (hits.isEmpty()) out.appendLine("nothing found (sources: ${m.sources().joinToString { it.id }.ifEmpty { "none; add one with: plugin sources add" }})")
                hits.forEach { h -> out.appendLine("${h.source}:${h.entry.id}  ${h.latest?.version ?: "no compatible version"}  ${h.cls.label}  [${h.entry.tags.joinToString(",")}]  ${h.entry.description}") }
                return 0
            }
            "show" -> {
                val ref = arg ?: return usage("show needs a plugin reference")
                val (insp, refused) = m.inspect(ref)
                if (insp == null) return show(refused!!)
                try { printInspection(insp, out) } finally { insp.file.delete() }
                return 0
            }
            "install" -> return show(m.install(arg ?: return usage("install needs a plugin reference"), consent(null), enable = !disabled, grantAll = all))
            "enable", "disable" -> return show(m.setEnabled(arg ?: return usage("$sub needs a plugin id"), sub == "enable"))
            "grants" -> {
                val id = arg ?: return usage("grants needs a plugin id")
                val row = m.status(id) ?: return show(ManagerResult.refused("NOT_INSTALLED", "$id is not installed"))
                if (grants.isEmpty() && !all && !none) {
                    out.appendLine("$id asks for: ${row.requested.joinToString().ifEmpty { "nothing" }}")
                    out.appendLine("granted: ${row.granted.joinToString().ifEmpty { "nothing" }}")
                    return 0
                }
                return show(m.setGrants(id, consent(row.requested) ?: emptySet()))
            }
            "updates" -> {
                val offers = m.checkUpdates()
                if (offers.isEmpty()) out.appendLine("everything the manager installed is up to date")
                offers.forEach { o -> out.appendLine("${o.id}  ${o.from} -> ${o.to}  (${o.source})" + if (o.grantsRaised) "  ASKS FOR NEW PERMISSIONS: ${o.newGrants.joinToString()}" else "") }
                return 0
            }
            "update" -> {
                val targets = if (arg == "--all") m.checkUpdates().map { it.id } else listOf(arg ?: return usage("update needs a plugin id or --all"))
                var code = 0
                for (id in targets) {
                    code = maxOf(code, show(m.update(id, consent(null), from, grantAll = all)))
                }
                if (targets.isEmpty()) out.appendLine("nothing to update")
                return code
            }
            "rollback" -> return show(m.rollback(arg ?: return usage("rollback needs a plugin id")))
            "remove" -> return show(m.remove(arg ?: return usage("remove needs a plugin id"), purge))
            "status" -> {
                val row = m.status(arg ?: return usage("status needs a plugin id")) ?: return show(ManagerResult.refused("NOT_INSTALLED", "$arg is not installed"))
                out.appendLine("${row.id} ${row.version}: ${row.cls.label}${if (row.bundled) " (bundled)" else ""}, ${row.tier.name.lowercase()} tier, ${if (row.enabled) "enabled" else "disabled"}")
                out.appendLine("source: ${row.source ?: "unlisted"}  licence: ${row.license ?: "not stated"}")
                out.appendLine("asks for: ${row.requested.joinToString().ifEmpty { "nothing" }}")
                out.appendLine("granted: ${row.granted.joinToString().ifEmpty { "nothing" }}")
                out.appendLine("kept for rollback: ${row.history.joinToString().ifEmpty { "none" }}")
                return 0
            }
            "sources" -> when (arg ?: "list") {
                "list" -> { m.sources().forEach { out.appendLine("${it.id}  ${it.cls}  ${it.url ?: it.dir}  ${if (it.enabled) "on" else "off"}") }; if (m.sources().isEmpty()) out.appendLine("no sources"); return 0 }
                "add" -> return show(m.addSource(CatalogSource(rest.getOrNull(2) ?: return usage("sources add needs an id"), cls ?: "custom", url, dir)))
                "remove" -> return show(m.removeSource(rest.getOrNull(2) ?: return usage("sources remove needs an id")))
                "refresh" -> {
                    val r = m.refresh(rest.getOrNull(2))
                    r.forEach { out.appendLine("${it.source}: ${it.message} (${it.plugins} plugins)") }
                    return if (r.any { !it.ok && !it.stale }) 1 else 0
                }
                else -> return usage("unknown sources command '$arg'")
            }
            "catalog" -> {
                if (arg != "validate") return usage("catalog needs: validate <catalog.json>")
                val f = File(rest.getOrNull(2) ?: return usage("catalog validate needs a file"))
                val catalog = runCatching { ManagerJson.decodeFromString(Catalog.serializer(), f.readText()) }.getOrElse { err.appendLine("error: ${f.path} is not a catalog: ${it.message?.lineSequence()?.first()}"); return 1 }
                val problems = m.validate(catalog, assets?.let(::File) ?: f.absoluteFile.parentFile)
                if (problems.isEmpty()) { out.appendLine("catalog ok: ${catalog.plugins.sumOf { it.versions.size }} version(s) of ${catalog.plugins.size} plugin(s)"); return 0 }
                problems.forEach { err.appendLine("problem: $it") }
                return 1
            }
            else -> return usage("unknown plugin command '$sub'")
        }
    }

    private fun printInspection(i: Inspection, out: Appendable) {
        out.appendLine("${i.id} ${i.version}: ${i.cls.label} plugin, ${i.tier.name.lowercase()} tier${if (i.installed) " (installed already)" else ""}")
        out.appendLine("source: ${i.source}  licence: ${i.license ?: "not stated"}")
        out.appendLine("signer: ${i.signer ?: "unsigned"}${if (i.signedByUserKey) " (a key you trust)" else ""}")
        out.appendLine("sha256: ${i.sha256}")
        out.appendLine("asks for: ${i.grants.joinToString().ifEmpty { "nothing" }}")
        i.warnings.forEach { out.appendLine("WARNING: $it") }
    }
}
