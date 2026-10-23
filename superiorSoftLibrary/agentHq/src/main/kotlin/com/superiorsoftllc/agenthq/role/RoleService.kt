package com.superiorsoftllc.agenthq.role

import com.superiorsoftllc.agenthq.protocol.RoleSave
import com.superiorsoftllc.agenthq.session.stateDir
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
class RoleService(private val dir: File) {
    @Volatile
    private var catalog: RoleCatalog = RoleBundle.catalog(dir)

    /** Every role in the catalog's stable order: the canonical ids first, then any extra ids sorted. */
    fun all(): List<Role> = catalog.all()

    fun role(id: String): Role? = catalog.role(id)

    /**
     * Validate [request], write it as a custom role file, reload, and return the role as the catalog now
     * holds it. Throws [IllegalArgumentException] with the reason, having written nothing, when the
     * definition is not acceptable.
     */
    @Synchronized
    fun save(request: RoleSave): Role {
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
        val persona = request.persona.replace("\r\n", "\n").replace('\r', '\n').trim()
        require(persona.length <= MAX_PERSONA_LENGTH) { "persona is longer than $MAX_PERSONA_LENGTH characters" }
        // ROLE-ICON-1: null keeps the stored icon (a client that predates icons must not wipe one), "" clears it.
        val icon = request.icon?.trim()?.let { raw ->
            require(raw.isEmpty() || ICON.matches(raw)) { "icon '${raw.take(40)}' is not an icon key (1-32 lowercase letters, digits or '-')" }
            raw
        } ?: catalog.role(id)?.icon.orEmpty()

        // The exact shape CustomRoles.parse reads back; the name is quoted-free on purpose (the parser trims quotes).
        val iconLine = if (icon.isEmpty()) "" else "icon: $icon\n"
        val text = "---\nname: $name\ncolor: $color\n$iconLine---\n$persona\n"
        // Parse it BEFORE touching the disk, so a definition the reader would reject is never written.
        val parsed = CustomRoles.parse(id, text)
        check(parsed.name == name) { "name '$name' would not read back unchanged" }
        check(parsed.icon == icon) { "icon '$icon' would not read back unchanged" }

        dir.mkdirs()
        val target = File(dir, id + CustomRoles.EXTENSION)
        val tmp = File(dir, ".$id${CustomRoles.EXTENSION}.tmp")
        tmp.writeText(text, Charsets.UTF_8)
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)

        catalog = RoleBundle.catalog(dir)
        return catalog.role(id) ?: error("role '$id' was written to ${target.name} but did not load back")
    }

    companion object {
        const val ENV = "AGENTHQ_ROLES_DIR"
        const val MAX_NAME_LENGTH = 60
        const val MAX_PERSONA_LENGTH = 20_000
        private val COLOR = Regex("#[0-9a-fA-F]{6}")

        /** ROLE-ICON-1: an icon key, the role-id alphabet, so it is safe as a front-matter value. */
        val ICON = Regex("[a-z0-9][a-z0-9-]{0,31}")

        /** `AGENTHQ_ROLES_DIR`, else `roles/` inside the daemon's state directory. */
        fun defaultDir(): File =
            System.getenv(ENV)?.takeIf { it.isNotBlank() }?.let(::File) ?: File(stateDir(), "roles")
    }
}
