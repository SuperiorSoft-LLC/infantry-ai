package com.superiorsoftllc.agenthq.role

import java.io.File

/**
 * User-defined roles, loaded from a directory of files following the **"filename is the role id"**
 * convention: `backend-ninja.md` defines the role `backend-ninja`.
 *
 * A file is optional YAML-ish front matter followed by the persona markdown:
 *
 * ```
 * ---
 * name: Backend Ninja
 * color: #0ea5e9
 * icon: storage
 * ---
 * You are the backend ninja. ...
 * ```
 *
 * Everything after the closing `---` is the persona, verbatim -- it is the system prompt a spawned
 * agent receives. Both front-matter keys are optional: a bare markdown file is a valid custom role
 * whose name is its id and whose colour comes from the palette.
 *
 * A file that cannot be read is **skipped and reported**, never thrown: the directory is the
 * operator's to edit, and one malformed file must not take the whole catalog down with it. The
 * returned [Load.problems] lines are what a UI shows instead of a silent disappearance.
 */
object CustomRoles {
    /** The extension a custom role file must carry. Markdown, because the body is a persona. */
    const val EXTENSION: String = ".md"

    data class Load(val roles: List<Role>, val problems: List<String>)

    /** Load every `*.md` in [dir]; a null or missing directory is an empty result, not an error. */
    fun loadFrom(dir: File?): Load {
        if (dir == null || !dir.isDirectory) return Load(emptyList(), emptyList())
        val files = (dir.listFiles() ?: return Load(emptyList(), emptyList()))
            .filter { it.isFile && it.name.endsWith(EXTENSION, ignoreCase = true) }
            .sortedBy { it.name.lowercase() }

        val roles = ArrayList<Role>(files.size)
        val problems = ArrayList<String>()
        val seen = HashSet<String>()
        for (file in files) {
            val id = file.name.dropLast(EXTENSION.length)
            if (!RoleIds.isValid(id)) {
                problems += "${file.name}: '$id' is not a role id (a lowercase letter then up to 31 lowercase letters, digits or '-')"
                continue
            }
            if (!seen.add(id)) {
                problems += "${file.name}: a role '$id' is already defined by an earlier file"
                continue
            }
            val parsed = runCatching { parse(id, file.readText(Charsets.UTF_8)) }
            parsed.fold(
                onSuccess = { roles += it },
                onFailure = { problems += "${file.name}: ${it.message}" },
            )
        }
        return Load(roles, problems)
    }

    /**
     * Parse one role file. [roleId] is the filename without its extension. Never throws for absent
     * front matter; only a front-matter block that opens and does not close is an error.
     */
    fun parse(roleId: String, text: String): Role {
        require(RoleIds.isValid(roleId)) { "'$roleId' is not a role id" }
        val normalized = text.removePrefix(Char(0xFEFF).toString()).replace("\r\n", "\n").replace('\r', '\n')
        val (front, body) = splitFrontMatter(normalized)
        val fields = front?.let(::parseFields) ?: emptyMap()
        val name = fields["name"]?.trim()?.takeIf { it.isNotEmpty() } ?: roleId
        return Role(
            id = roleId,
            name = name,
            color = RoleBundle.normalizeColor(fields["color"].orEmpty(), roleId),
            persona = body.trim(),
            source = RoleSource.CUSTOM,
            // ROLE-ICON-1: an optional `icon:` key; a value that is not an icon key reads as none (the client default).
            icon = fields["icon"]?.trim()?.lowercase()?.takeIf { RoleService.ICON.matches(it) }.orEmpty(),
            description = fields["description"]?.trim().orEmpty(),
        )
    }

    /** Returns the front-matter text (null when absent) and the body after it. */
    private fun splitFrontMatter(text: String): Pair<String?, String> {
        if (!text.startsWith("---\n")) return null to text
        val close = text.indexOf("\n---", startIndex = 4)
        if (close < 0) throw IllegalArgumentException("front matter opens with '---' but never closes")
        val front = text.substring(4, close)
        val afterDelimiter = close + 4
        val body = if (afterDelimiter >= text.length) "" else text.substring(afterDelimiter).removePrefix("\n")
        return front to body
    }

    private fun parseFields(front: String): Map<String, String> = front.lineSequence()
        .mapNotNull { line ->
            val colon = line.indexOf(':')
            if (colon <= 0) return@mapNotNull null
            val key = line.substring(0, colon).trim().lowercase()
            val value = line.substring(colon + 1).trim().trim('"', '\'')
            key.takeIf { it.isNotEmpty() }?.let { it to value }
        }
        .toMap()
}
