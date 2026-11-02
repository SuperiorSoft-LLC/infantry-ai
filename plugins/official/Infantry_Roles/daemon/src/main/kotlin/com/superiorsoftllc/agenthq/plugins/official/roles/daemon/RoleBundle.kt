package com.superiorsoftllc.agenthq.plugins.official.roles.daemon

import com.superiorsoftllc.agenthq.protocol.RoleIds

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The bundled role catalog, read as **data** from `/roles/index.json` beside its persona files.
 *
 * The whole point of a manifest is replaceability: swapping the bundled set is editing resources,
 * never editing Kotlin. There is deliberately **no `when` over role ids** and no sealed type here --
 * a role a future bundle adds is a row in JSON, not a branch in this file.
 *
 * An **absent or empty manifest is legal**: the catalog then falls back to the [RoleIds] contract and
 * generated badges, so a build that ships no role resources still works.
 */
object RoleBundle {
    const val MANIFEST_PATH: String = "/roles/index.json"
    private const val DIR: String = "/roles/"
    private val json = Json { ignoreUnknownKeys = true }

    /** One manifest row. `persona` is a filename relative to `/roles/`, not the text itself. */
    @Serializable
    internal data class Entry(
        val id: String,
        val name: String = "",
        val color: String = "",
        val persona: String? = null,
        /** ROLE-ICON-1: an optional icon key; absent = the client's default for the role. */
        val icon: String = "",
        val responseDetail: String = "",
        val responseMaxChars: Int? = null,
        val responseBodyChars: Int? = null,
    )

    @Serializable
    internal data class Manifest(val version: Int = 1, val roles: List<Entry> = emptyList())

    /**
     * Every bundled role, in manifest order.
     *
     * [readText] maps a classpath-style path (`/roles/index.json`) to its text, or null when absent;
     * it is injected so the loader can be tested without a classpath. A `persona` file the manifest
     * names but that is missing is a **broken bundle**, not an empty one, and throws -- a silently
     * dropped persona would ship an agent with no instructions and no sign of why.
     */
    fun load(readText: (String) -> String?, manifestPath: String = MANIFEST_PATH): List<Role> {
        val text = readText(manifestPath)?.removePrefix(Char(0xFEFF).toString())
        if (text.isNullOrBlank()) return emptyList()
        val manifest = try {
            json.decodeFromString(Manifest.serializer(), text)
        } catch (e: Exception) {
            throw IllegalStateException("$manifestPath is not a valid role manifest: ${e.message}", e)
        }
        val ids = HashSet<String>()
        return manifest.roles.map { entry ->
            require(RoleIds.isValid(entry.id)) {
                "$manifestPath has role id '${entry.id}': a role id is a lowercase letter then up to 31 lowercase letters, digits or '-'"
            }
            require(ids.add(entry.id)) { "$manifestPath names role '${entry.id}' twice" }
            val persona = entry.persona?.takeIf { it.isNotBlank() }?.let { file ->
                readText(DIR + file)
                    ?: throw IllegalStateException("role '${entry.id}' names persona '$file' but $DIR$file is not on the classpath")
            }?.replace("\r\n", "\n")?.replace('\r', '\n') ?: ""
            Role(
                id = entry.id,
                name = entry.name.trim().ifBlank { entry.id },
                color = normalizeColor(entry.color, entry.id),
                persona = persona,
                source = RoleSource.BUILT_IN,
                icon = entry.icon.trim().lowercase().takeIf { RoleService.ICON.matches(it) }.orEmpty(),
                responseDetail = entry.responseDetail.trim().lowercase().takeIf { it in Role.DETAILS }.orEmpty(),
                responseMaxChars = entry.responseMaxChars?.takeIf { it >= 0 },
                responseBodyChars = entry.responseBodyChars?.takeIf { it >= 0 },
            )
        }
    }

    /** A `#rgb` / `#rrggbb` / `#rrggbbaa` colour, or the palette fallback when the manifest's is unusable. */
    internal fun normalizeColor(raw: String, roleId: String): String {
        val value = raw.trim()
        val valid = value.matches(Regex("#([0-9a-fA-F]{3}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})"))
        return if (valid) value.lowercase() else RolePalette.colorFor(roleId)
    }

    /** The default [readText]: the context class loader's classpath, UTF-8, null when absent. */
    fun classpathReader(): (String) -> String? = { path ->
        val loader = Thread.currentThread().contextClassLoader ?: RoleBundle::class.java.classLoader
        // ClassLoader.getResourceAsStream takes no leading slash (unlike Class.getResourceAsStream).
        loader.getResourceAsStream(path.removePrefix("/"))?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
    }

    /** Convenience: load from `customDir` (if any) over the classpath bundle. */
    fun catalog(customDir: File? = null): RoleCatalog =
        RoleCatalog(load(classpathReader()), CustomRoles.loadFrom(customDir).roles)
}
