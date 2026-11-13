// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.roles.daemon

import com.superiorsoftllc.infantry.protocol.RoleIds


import com.superiorsoftllc.infantry.protocol.RoleSave
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Contract 1.12.0: the daemon's role catalog, readable (`role_list`) and extensible (`role_save`) over
 * the wire, so a client -- desktop, Android or remote -- never reads role files itself.
 *
 * The catalog is [RoleCatalog] exactly as Phase 10a built it (custom file > bundled manifest > canonical
 * contract). Saving a role writes ONE custom role file, `<dir>/<id>.md`, in the format [CustomRoles]
 * already reads (front matter `name` / `color`, persona body), and reloads the catalog -- so a saved role
 * survives a restart with no second store, and an operator can still edit the file by hand.
 *
 * Saving a canonical id (`backend`) overrides that role for this daemon only; saving a new id adds a role.
 * Nothing here deletes a role: removing the file restores the built-in definition.
 *
 * @param dir the custom roles directory; created on the first save.
 */
class RoleService(
    private val dir: File,
    /**
     * ROLE-PLUGIN-1: the ids of the modules loaded now; null = unknown (a test, a host without a plugin view), and then no plugin
     * role reads as disabled. A role whose [Role.plugin] is not in the set reads [Role.disabled].
     */
    private val loadedModules: () -> Set<String>? = { null },
) {
    @Volatile
    private var catalog: RoleCatalog = RoleBundle.catalog(dir)

    /** Every role in the catalog's stable order: the canonical ids first, then any extra ids sorted. */
    fun all(): List<Role> {
        val loaded = runCatching(loadedModules).getOrNull()
        return catalog.all().map { overlay(it, loaded) }
    }

    fun role(id: String): Role? = catalog.role(id)?.let { overlay(it, runCatching(loadedModules).getOrNull()) }

    /** ROLE-PLUGIN-1: a plugin role reads disabled while its module is not loaded (computed, never stored). */
    private fun overlay(role: Role, loaded: Set<String>?): Role =
        if (role.plugin.isNotEmpty() && loaded != null && role.plugin !in loaded) role.copy(disabled = true) else role

    /**
     * Validate [request], write it as a custom role file, reload, and return the role as the catalog now
     * holds it. Throws [IllegalArgumentException] with the reason, having written nothing, when the
     * definition is not acceptable.
     *
     * ROLE-PLUGIN-1: saving a plugin's role keeps its [Role.plugin] and marks it [Role.edited], so the module never rewrites it;
     * [RoleSave.cards] / [RoleSave.tools] null keep the stored lists. Saving an id clears its "owner removed" mark.
     */
    @Synchronized
    fun save(request: RoleSave): Role {
        val id = request.id.trim()
        val stored = catalog.role(id)?.takeIf { it.source == RoleSource.CUSTOM }
        val written = write(
            request,
            plugin = stored?.plugin.orEmpty(),
            edited = stored?.plugin?.isNotEmpty() == true,
            cards = request.cards ?: stored?.cards.orEmpty(),
            tools = request.tools ?: stored?.tools.orEmpty(),
        )
        unmarkRemoved(id)
        return written
    }

    /** What [seed] did with one declared role. */
    enum class Seeded { ADDED, REFRESHED, KEPT }

    /**
     * ROLE-PLUGIN-1: seeds the role [declared] by module [module] (persona text already resolved). Written when the id is free;
     * rewritten when it is still that module's own unedited copy and the declaration changed (a plugin update); KEPT otherwise:
     * an owner edit, an owner's own role of that id, another module's role, a built-in role, or an id the owner removed.
     * Throws [IllegalArgumentException] for a declaration the catalog would refuse.
     */
    @Synchronized
    fun seed(module: String, declared: RoleSave): Seeded {
        val id = declared.id.trim()
        if (id in removed()) return Seeded.KEPT
        val existing = catalog.role(id)
        val bundled = RoleBundle.catalog(null).role(id) != null || id in RoleIds.BUILT_IN
        val cards = declared.cards.orEmpty()
        val tools = declared.tools.orEmpty()
        return when {
            existing == null && !bundled -> { write(declared, module, edited = false, cards, tools); Seeded.ADDED }
            existing == null || existing.source != RoleSource.CUSTOM -> Seeded.KEPT
            existing.plugin != module || existing.edited -> Seeded.KEPT
            same(existing, declared, cards, tools) -> Seeded.KEPT
            else -> { write(declared, module, edited = false, cards, tools); Seeded.REFRESHED }
        }
    }

    private fun same(r: Role, d: RoleSave, cards: List<String>, tools: List<String>): Boolean =
        r.name == d.name.trim() && r.persona == normalize(d.persona) &&
            (d.color?.trim()?.takeIf { it.isNotEmpty() }?.let { r.color == it.lowercase() } ?: true) &&
            (d.icon?.let { r.icon == it.trim() } ?: true) && (d.description?.let { r.description == it.trim() } ?: true) &&
            r.cards == cards.map { it.trim() }.filter { it.isNotEmpty() }.distinct() &&
            r.tools == tools.map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    private fun normalize(persona: String) = persona.replace("\r\n", "\n").replace('\r', '\n').trim()

    /** Validates and writes one role file (the shape [CustomRoles.parse] reads back), reloads, and returns the stored role. */
    private fun write(request: RoleSave, plugin: String, edited: Boolean, cards: List<String>, tools: List<String>): Role {
        val id = request.id.trim()
        require(RoleIds.isValid(id)) {
            "'${id.take(40)}' is not a role id: a lowercase letter, then up to 31 lowercase letters, digits or '-'"
        }
        val name = request.name.trim()
        require(name.isNotEmpty()) { "name is blank" }
        require(name.length <= MAX_NAME_LENGTH) { "name is longer than $MAX_NAME_LENGTH characters" }
        require(name.none { it.isISOControl() }) { "name contains a control character" }
        val color = request.color?.trim()?.takeIf { it.isNotEmpty() }?.let { raw ->
            require(COLOR.matches(raw)) { "colour '${raw.take(20)}' is not #rrggbb" }
            raw.lowercase()
        } ?: catalog.role(id)?.color ?: RolePalette.colorFor(id)
        val persona = normalize(request.persona)
        require(persona.length <= MAX_PERSONA_LENGTH) { "persona is longer than $MAX_PERSONA_LENGTH characters" }
        // ROLE-ICON-1: null keeps the stored icon (a client that predates icons must not wipe one), "" clears it.
        val icon = request.icon?.trim()?.let { raw ->
            require(raw.isEmpty() || ICON.matches(raw)) { "icon '${raw.take(40)}' is not an icon key (1-32 lowercase letters, digits or '-')" }
            raw
        } ?: catalog.role(id)?.icon.orEmpty()

        // The exact shape CustomRoles.parse reads back; the name is quoted-free on purpose (the parser trims quotes).
        // ROLE-EDIT-1: null keeps the stored description, "" clears it; one line, it lives in the front matter.
        val description = request.description?.trim()?.also { raw ->
            require(raw.length <= MAX_DESCRIPTION_LENGTH) { "description is longer than $MAX_DESCRIPTION_LENGTH characters" }
            require(raw.none { it.isISOControl() }) { "description must be a single line" }
        } ?: catalog.role(id)?.description.orEmpty()
        // ROLE-PLUGIN-1: preference ids, one front-matter line per list; an id that is not one is refused, not dropped.
        val prefs = listOf("cards" to cards, "tools" to tools).map { (key, ids) ->
            val clean = ids.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            clean.firstOrNull { !CustomRoles.PREF_ID.matches(it) }?.let { bad -> throw IllegalArgumentException("$key entry '${bad.take(40)}' is not an id (letters, digits, _ . : -)") }
            require(clean.size <= MAX_PREFERENCES) { "more than $MAX_PREFERENCES $key" }
            key to clean
        }

        val iconLine = if (icon.isEmpty()) "" else "icon: $icon\n"
        val descriptionLine = if (description.isEmpty()) "" else "description: $description\n"
        val pluginLines = if (plugin.isEmpty()) "" else "plugin: $plugin\n" + (if (edited) "edited: true\n" else "")
        val prefLines = prefs.joinToString("") { (key, ids) -> if (ids.isEmpty()) "" else "$key: ${ids.joinToString(", ")}\n" }
        val text = "---\nname: $name\ncolor: $color\n$iconLine$descriptionLine$pluginLines$prefLines---\n$persona\n"
        // Parse it BEFORE touching the disk, so a definition the reader would reject is never written.
        val parsed = CustomRoles.parse(id, text)
        check(parsed.name == name) { "name '$name' would not read back unchanged" }
        check(parsed.icon == icon) { "icon '$icon' would not read back unchanged" }
        check(parsed.description == description) { "description would not read back unchanged" }
        check(parsed.plugin == plugin) { "plugin '$plugin' would not read back unchanged" }

        dir.mkdirs()
        val target = File(dir, id + CustomRoles.EXTENSION)
        val tmp = File(dir, ".$id${CustomRoles.EXTENSION}.tmp")
        tmp.writeText(text, Charsets.UTF_8)
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)

        catalog = RoleBundle.catalog(dir)
        return role(id) ?: error("role '$id' was written to ${target.name} but did not load back")
    }

    /** ROLE-PLUGIN-1: plugin role ids the owner deleted; their module never seeds them again (a save of the id clears the mark). */
    private val removedFile: File get() = File(dir, REMOVED_FILE)

    private fun removed(): Set<String> = if (!removedFile.isFile) emptySet() else
        runCatching { removedFile.readLines(Charsets.UTF_8).map { it.trim() }.filter { RoleIds.isValid(it) }.toSet() }.getOrDefault(emptySet())

    private fun writeRemoved(ids: Set<String>) {
        dir.mkdirs()
        removedFile.writeText(ids.sorted().joinToString("\n", postfix = "\n"), Charsets.UTF_8)
    }

    private fun unmarkRemoved(id: String) {
        val now = removed()
        if (id in now) writeRemoved(now - id)
    }

    /**
     * ROLE-EDIT-1: delete the custom role file of [rawId]. Refused (nothing touched) when [liveRoleIds] holds the role,
     * and for a bundled role with no custom file (nothing to delete). Returns the role as the catalog now holds it: the
     * bundled definition when a custom override was reset, null when a pure custom role was removed.
     */
    @Synchronized
    fun delete(rawId: String, liveRoleIds: Set<String>): Role? {
        val id = rawId.trim()
        require(RoleIds.isValid(id)) { "'${id.take(40)}' is not a role id" }
        val file = File(dir, id + CustomRoles.EXTENSION)
        require(file.isFile) {
            if (RoleBundle.catalog(null).role(id) != null) "role '$id' is built in and has no custom definition to delete"
            else "unknown role '$id'"
        }
        require(id !in liveRoleIds) { "role '$id' has live sessions; end them first" }
        val wasPlugin = catalog.role(id)?.plugin?.isNotEmpty() == true
        Files.delete(file.toPath())
        if (wasPlugin) writeRemoved(removed() + id)
        catalog = RoleBundle.catalog(dir)
        return if (id in RoleIds.BUILT_IN || RoleBundle.catalog(null).role(id) != null) catalog.role(id) else null
    }

    companion object {
        const val MAX_DESCRIPTION_LENGTH = 200
        const val ENV = "INFANTRY_ROLES_DIR"
        const val MAX_NAME_LENGTH = 60
        const val MAX_PERSONA_LENGTH = 20_000
        /** ROLE-PLUGIN-1: the most preferred card types, and separately tool names, one role keeps. */
        const val MAX_PREFERENCES = 64
        /** ROLE-PLUGIN-1: the file (in the roles directory, not a role: no `.md`) listing plugin roles the owner deleted. */
        const val REMOVED_FILE = ".plugin-roles-removed"
        private val COLOR = Regex("#[0-9a-fA-F]{6}")

        /** ROLE-ICON-1: an icon key, the role-id alphabet, so it is safe as a front-matter value. */
        val ICON = Regex("[a-z0-9][a-z0-9-]{0,31}")

    }
}
