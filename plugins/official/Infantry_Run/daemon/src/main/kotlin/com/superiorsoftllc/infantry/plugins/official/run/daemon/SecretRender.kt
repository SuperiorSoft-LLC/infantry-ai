// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.run.daemon

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFilePermissions

/**
 * SECRET-RENDER-1: the ONE placeholder syntax and the pure substitution. `{{secret:NAME}}` is the keystore secret NAME's
 * single value; `{{secret:NAME#field}}` one named field of a multi-field secret. `{{!secret:NAME}}` is the escape: it is
 * left exactly as written. The syntax is the same in every text format; nothing here parses .env, JSON or YAML.
 */
object SecretTemplate {
    const val PLACEHOLDER_OPEN = "{{secret:"
    const val ESCAPED_OPEN = "{{!secret:"
    private const val VALUE_FIELD = "value"
    private val REF = Regex("""\{\{(!?)secret:([a-z0-9][a-z0-9._-]{0,63})(?:#([A-Za-z_][A-Za-z0-9_]{0,63}))?\}\}""")

    /** The placeholder a UI shows for secret [name]. */
    fun placeholder(name: String) = "$PLACEHOLDER_OPEN$name}}"

    class Ref(val name: String, val field: String) {
        val label: String = if (field == VALUE_FIELD) name else "$name#$field"
    }

    /** What a render did: names only, never a value. [text] is the output and must never be logged. */
    class Rendered(val text: String, val filled: List<String>, val missing: List<String>)

    fun refs(text: String): List<Ref> =
        REF.findAll(text).filter { it.groupValues[1].isEmpty() }
            .map { Ref(it.groupValues[2], it.groupValues[3].ifEmpty { VALUE_FIELD }) }.distinctBy { it.label }.toList()

    /** [lookup] gives the value of (name, field) or null; a null is "missing" and its placeholder stays in the text. */
    fun render(text: String, lookup: (String, String) -> String?): Rendered {
        val filled = LinkedHashSet<String>()
        val missing = LinkedHashSet<String>()
        val sb = StringBuilder(text.length)
        var last = 0
        for (m in REF.findAll(text)) {
            sb.append(text, last, m.range.first)
            last = m.range.last + 1
            if (m.groupValues[1].isNotEmpty()) { sb.append(m.value); continue }
            val ref = Ref(m.groupValues[2], m.groupValues[3].ifEmpty { VALUE_FIELD })
            val v = lookup(ref.name, ref.field)
            if (v == null) { missing += ref.label; sb.append(m.value) } else { filled += ref.label; sb.append(v) }
        }
        sb.append(text, last, text.length)
        return Rendered(sb.toString(), filled.toList(), missing.toList())
    }
}

/** The outcome of a file render: names and the written path only. */
class RenderResult(val filled: List<String>, val missing: List<String>, val out: File?, val error: String? = null, val reasons: String = "") {
    val ok: Boolean get() = error == null

    /** Lines safe for any log, CLI line or tool result: names and a path, never a value. */
    fun summary(check: Boolean): String = buildString {
        if (error != null) {
            append("render refused: $error")
            if (missing.isNotEmpty()) append("\nmissing: ${missing.joinToString()}")
            return@buildString
        }
        if (filled.isNotEmpty()) appendLine("${if (check) "found" else "filled"}: ${filled.joinToString()}")
        if (missing.isNotEmpty()) appendLine("missing: ${missing.joinToString()}" + if (reasons.isNotEmpty()) " ($reasons)" else "")
        if (filled.isEmpty() && missing.isEmpty()) appendLine("no secret placeholders found")
        if (out != null) append("wrote: ${out.path}") else append(if (check) "checked only, nothing written" else "nothing written")
    }.trimEnd()
}

/**
 * SECRET-RENDER-1: render a text file's placeholders from the keystore. [resolve] is (name, project) -> fields (project
 * scope first, then app: the run_secrets resolution); [restricted] is the owner policy; [maxBytes] the named limit.
 */
class SecretRenderer(
    private val resolve: (String, String?) -> Map<String, String>?,
    private val restricted: () -> Boolean = { false },
    private val maxBytes: () -> Long = { DEFAULT_MAX_BYTES },
) {
    fun render(template: File, out: File?, project: String?, allowMissing: Boolean = false, check: Boolean = false): RenderResult {
        fun no(why: String, missing: List<String> = emptyList()) = RenderResult(emptyList(), missing, null, why)
        if (restricted()) return no("secrets are locked under a restricted policy")
        if (!template.isFile) return no("template '${template.name}' is not a file")
        if (template.length() > maxBytes()) return no("template '${template.name}' is over limits.secrets.renderMaxBytes (${maxBytes()} bytes)")
        val text = decodeText(template.readBytes())
            ?: return no("'${template.name}' is not a text file (binary or not UTF-8); only text files can be rendered")
        val cache = HashMap<String, Map<String, String>?>()
        val r = SecretTemplate.render(text) { name, field ->
            val fields = cache.getOrPut(name) { try { resolve(name, project) } catch (e: Exception) { null } }
            // SECRET-RENDER-2: `{{secret:NAME}}` on a ONE-field secret is that field, whatever the field is called.
            fields?.get(field) ?: if (field == "value" && fields != null && fields.size == 1) fields.values.first() else null
        }
        val why = r.missing.map { label ->
            val name = label.substringBefore('#')
            val fields = cache[name]
            when {
                fields == null -> "$name: not found in ${if (project.isNullOrBlank()) "app scope" else "project or app scope"}"
                label.contains('#') -> "$label: secret '$name' has no field '${label.substringAfter('#')}' (fields: ${fields.keys.joinToString()})"
                else -> "$label: secret '$name' has ${fields.size} fields (${fields.keys.joinToString()}); use {{secret:$name#FIELD}}"
            }
        }.joinToString("; ")
        if (check) return RenderResult(r.filled, r.missing, null, reasons = why)
        if (r.missing.isNotEmpty() && !allowMissing) return no("missing secrets, nothing written ($why)", r.missing)
        val target = out ?: return no("an output file is required")
        if (sameFile(template, target)) return no("the output may not be the template itself")
        return try {
            writePrivate(target, r.text)
            RenderResult(r.filled, r.missing, target)
        } catch (e: Exception) { no("could not write '${target.name}' (${e::class.simpleName})") }
    }

    /** [render] with paths as a caller wrote them: a relative one is inside [root] (the project folder); absolute when there is none. */
    fun renderIn(root: File?, template: String, out: String?, project: String?, allowMissing: Boolean = false, check: Boolean = false): RenderResult {
        fun file(p: String): File = File(p).let { if (it.isAbsolute || root == null) it else File(root, p) }
        return render(file(template), out?.let(::file), project, allowMissing, check)
    }

    companion object {
        /** The setting `limits.secrets.renderMaxBytes` default, used until the plugin points [maxBytes] at the setting. */
        const val DEFAULT_MAX_BYTES = 1_048_576L

        /** UTF-8 text without NUL bytes, or null. */
        fun decodeText(bytes: ByteArray): String? {
            if (bytes.any { it == 0.toByte() }) return null
            return try {
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            } catch (e: CharacterCodingException) { null }
        }

        private fun sameFile(a: File, b: File): Boolean =
            a.canonicalFile == b.canonicalFile || (b.exists() && runCatching { Files.isSameFile(a.toPath(), b.toPath()) }.getOrDefault(false))

        /** Writes [text] to [target], owner-only from the first byte: 0600 on Unix, an owner-only ACL on Windows. */
        fun writePrivate(target: File, text: String) {
            val dir = target.absoluteFile.parentFile ?: File(".")
            dir.mkdirs()
            val tmp = File(dir, ".${target.name}.${System.nanoTime()}.tmp").toPath()
            if (Files.getFileStore(dir.toPath()).supportsFileAttributeView("posix")) {
                Files.createFile(tmp, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
            } else Files.createFile(tmp)
            try {
                restrictAcl(tmp)
                Files.write(tmp, text.toByteArray(Charsets.UTF_8))
                Files.move(tmp, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } finally { Files.deleteIfExists(tmp) }
        }

        private fun restrictAcl(path: java.nio.file.Path) {
            val view = Files.getFileAttributeView(path, AclFileAttributeView::class.java) ?: return
            val me = view.owner
            view.acl = listOf(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(me).setPermissions(AclEntryPermission.entries.toSet()).build())
        }
    }
}
