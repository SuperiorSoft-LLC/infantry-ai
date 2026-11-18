// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.infantry.ui.protocol.Envelope
import com.superiorsoftllc.infantry.ui.shell.LinkReply
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** VAULT-REVEAL-1: a value the daemon handed back after the OS user check, and how long it may be shown / stay on the clipboard. */
class RevealedValue(val value: String, val method: String, val showSeconds: Long, val clearSeconds: Long) {
    override fun toString(): String = "RevealedValue(method=$method)"
}

/** VAULT-REVEAL-1: an import's answer; [applied] false = nothing written, [conflicts] (ids) still need keep / replace. */
class BackupImportOutcome(
    val applied: Boolean, val added: List<String>, val replaced: List<String>, val kept: List<String>, val same: List<String>, val conflicts: List<String>,
) {
    fun summary(): String = "Imported: ${added.size} added, ${replaced.size} replaced, ${kept.size} kept, ${same.size} already the same."
}

/**
 * VAULT-REVEAL-1: puts a value on the clipboard and clears it after the daemon's `limits.secrets.clipboardClearSeconds`,
 * unless something else was copied since. The clear runs in an app-lifetime scope, so closing Settings does not keep the value
 * on the clipboard. A clipboard this platform cannot read back (null) is cleared too.
 */
class SecretClipboard(private val scope: CoroutineScope) {
    private var pending: Job? = null

    fun copy(value: String, clearAfterMs: Long, read: () -> String?, write: (String) -> Unit) {
        write(value)
        pending?.cancel()
        pending = scope.launch {
            delay(clearAfterMs)
            val now = runCatching(read).getOrNull()
            if (now == null || now == value) write("")
        }
    }

    companion object {
        val App: SecretClipboard by lazy { SecretClipboard(CoroutineScope(SupervisorJob() + Dispatchers.Main)) }
    }
}

/** VAULT-REVEAL-1: the frames' bodies and answers (contract 1.157.0). A value is never kept beyond the [RevealedValue] it arrives in. */
object SecretVaultWire {
    const val SINCE_MAJOR = 1
    const val SINCE_MINOR = 157
    const val DEFAULT_BACKUP_PATH = "~/infantry-vault-backup.json"

    private val json = Json { ignoreUnknownKeys = true }

    fun reveal(name: String, field: String, project: String?, purpose: String): String = JsonObject(buildMap {
        put("name", JsonPrimitive(name)); put("field", JsonPrimitive(field)); put("purpose", JsonPrimitive(purpose))
        project?.let { put("project", JsonPrimitive(it)) }
    }).toString()

    fun export(path: String, passphrase: String): String =
        JsonObject(mapOf("path" to JsonPrimitive(path), "passphrase" to JsonPrimitive(passphrase))).toString()

    fun import(path: String, passphrase: String, resolutions: Map<String, String>): String = JsonObject(mapOf(
        "path" to JsonPrimitive(path), "passphrase" to JsonPrimitive(passphrase),
        "resolutions" to JsonObject(resolutions.mapValues { JsonPrimitive(it.value) }),
    )).toString()

    /** The daemon's answer as [T], or its refusal (the reason without the frame-name prefix). */
    fun <T> result(type: String, reply: LinkReply?, parse: (JsonObject) -> T): Result<T> = when {
        reply == null -> Result.failure(IllegalStateException("not connected to the daemon"))
        reply.refused -> Result.failure(IllegalStateException(reply.data.removePrefix("$type refused: ").removePrefix("$type failed: ")))
        else -> runCatching { parse(json.parseToJsonElement(reply.data).jsonObject) }
    }

    fun revealed(o: JsonObject) = RevealedValue(
        o.str("value") ?: error("no value in the answer"), o.str("method").orEmpty(),
        o["showSeconds"]?.jsonPrimitive?.longOrNull ?: 30, o["clearSeconds"]?.jsonPrimitive?.longOrNull ?: 30,
    )

    fun exported(o: JsonObject): String = "Saved ${o["secrets"]?.jsonPrimitive?.contentOrNull ?: "?"} secrets to ${o.str("path").orEmpty()}"

    fun imported(o: JsonObject) = BackupImportOutcome(
        o["applied"]?.jsonPrimitive?.contentOrNull == "true", o.list("added"), o.list("replaced"), o.list("kept"), o.list("same"), o.list("conflicts"),
    )

    private fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.list(key: String) = this[key]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()

    /** The request types, for [result]. */
    const val REVEAL = Envelope.SECRET_REVEAL
    const val EXPORT = Envelope.SECRET_BACKUP_EXPORT
    const val IMPORT = Envelope.SECRET_BACKUP_IMPORT
}

const val SECRET_FIELD_VALUE_TAG = "secret-field-value"
const val SECRET_FIELD_REVEAL_TAG = "secret-field-reveal"
const val SECRET_FIELD_COPY_VALUE_TAG = "secret-field-copy-value"
const val SECRET_FIELD_VAULT_NOTE_TAG = "secret-field-vault-note"
const val SECRET_BACKUP_TAG = "secret-backup"
const val SECRET_BACKUP_EXPORT_PATH_TAG = "secret-backup-export-path"
const val SECRET_BACKUP_EXPORT_PASS_TAG = "secret-backup-export-pass"
const val SECRET_BACKUP_EXPORT_PASS2_TAG = "secret-backup-export-pass2"
const val SECRET_BACKUP_EXPORT_TAG = "secret-backup-export"
const val SECRET_BACKUP_IMPORT_PATH_TAG = "secret-backup-import-path"
const val SECRET_BACKUP_IMPORT_PASS_TAG = "secret-backup-import-pass"
const val SECRET_BACKUP_IMPORT_TAG = "secret-backup-import"
const val SECRET_BACKUP_CONFLICT_TAG = "secret-backup-conflict"
const val SECRET_BACKUP_RESULT_TAG = "secret-backup-result"

/**
 * VAULT-REVEAL-1: a field's masked tail, or its value for `showSeconds` after Reveal, then Reveal / Copy. Both ask the
 * daemon, which asks the person at its computer (Windows Hello) first; nothing here keeps the value once it is masked again.
 */
@Composable
internal fun RowScope.SecretFieldValue(
    name: String, field: String, tail: String, reveal: (suspend (name: String, field: String, purpose: String) -> Result<RevealedValue>)?,
) {
    var shown by remember(name, field) { mutableStateOf<RevealedValue?>(null) }
    var busy by remember(name, field) { mutableStateOf(false) }
    var note by remember(name, field) { mutableStateOf("") }
    var noteBad by remember(name, field) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val clip = LocalClipboardManager.current
    LaunchedEffect(shown) {
        val s = shown ?: return@LaunchedEffect
        delay(s.showSeconds * 1_000L)
        shown = null
    }
    Text(
        shown?.value ?: tail.ifEmpty { "set" }, fontFamily = Mono, fontSize = 12.sp, color = if (shown != null) Palette.Text else Palette.Muted,
        modifier = Modifier.weight(1f, fill = false).testTag("$SECRET_FIELD_VALUE_TAG-$name-$field"),
    )
    if (reveal == null) return
    if (busy || note.isNotEmpty()) {
        Text(
            if (busy) "Confirm it is you in the Windows prompt…" else note, fontSize = 11.sp, color = if (noteBad && !busy) Palette.Danger else Palette.Muted,
            modifier = Modifier.weight(1f, fill = false).testTag("$SECRET_FIELD_VAULT_NOTE_TAG-$name-$field"),
        )
    }
    TextButton(
        enabled = !busy,
        onClick = {
            if (shown != null) { shown = null; return@TextButton }
            busy = true
            scope.launch {
                reveal(name, field, "reveal").fold({ shown = it; note = ""; noteBad = false }, { note = it.message ?: "refused"; noteBad = true })
                busy = false
            }
        },
        modifier = Modifier.testTag("$SECRET_FIELD_REVEAL_TAG-$name-$field"),
    ) { Text(if (shown != null) "Hide" else "Reveal", fontSize = 12.sp, color = Palette.Text) }
    TextButton(
        enabled = !busy,
        onClick = {
            busy = true
            scope.launch {
                reveal(name, field, "copy").fold({ v ->
                    SecretClipboard.App.copy(v.value, v.clearSeconds * 1_000L, read = { clip.getText()?.text }, write = { clip.setText(AnnotatedString(it)) })
                    note = "Copied; the clipboard clears in ${v.clearSeconds}s"; noteBad = false
                }, { note = it.message ?: "refused"; noteBad = true })
                busy = false
            }
        },
        modifier = Modifier.testTag("$SECRET_FIELD_COPY_VALUE_TAG-$name-$field"),
    ) { Text("Copy value", fontSize = 12.sp, color = Palette.Text) }
}

/**
 * VAULT-REVEAL-1: export every secret to one passphrase-encrypted file on the daemon's computer (after the OS user check),
 * or merge one back in, asking keep / replace for each secret that exists here with other values.
 */
@Composable
internal fun VaultBackupCard(
    export: suspend (path: String, passphrase: String) -> Result<String>,
    import: suspend (path: String, passphrase: String, resolutions: Map<String, String>) -> Result<BackupImportOutcome>,
) {
    var outPath by remember { mutableStateOf(SecretVaultWire.DEFAULT_BACKUP_PATH) }
    var pass by remember { mutableStateOf("") }
    var pass2 by remember { mutableStateOf("") }
    var inPath by remember { mutableStateOf(SecretVaultWire.DEFAULT_BACKUP_PATH) }
    var inPass by remember { mutableStateOf("") }
    var conflicts by remember { mutableStateOf<List<String>>(emptyList()) }
    var answers by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var result by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(Modifier.secretCard().testTag(SECRET_BACKUP_TAG), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SecretsHeading(
            "Vault backup",
            "Every secret in one file, encrypted with a passphrase you choose, so a lost or wiped PC does not lose them. Keep it on a USB " +
                "stick or in cloud storage: without the passphrase it cannot be opened, and the passphrase cannot be recovered. " +
                "Export asks Windows Hello first. Paths are on the daemon's computer.",
        )
        SecretTextField(outPath, { outPath = it }, "Backup file to create", false, Modifier.testTag(SECRET_BACKUP_EXPORT_PATH_TAG))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            MaskedInput(pass, { pass = it }, "Passphrase", Modifier.weight(1f).testTag(SECRET_BACKUP_EXPORT_PASS_TAG))
            MaskedInput(pass2, { pass2 = it }, "Repeat passphrase", Modifier.weight(1f).testTag(SECRET_BACKUP_EXPORT_PASS2_TAG))
        }
        if (pass2.isNotEmpty() && pass != pass2) Text("The two passphrases differ.", fontSize = 11.sp, color = Palette.Danger)
        SecretButton("Export backup", outPath.isNotBlank() && pass.isNotEmpty() && pass == pass2 && !busy, SECRET_BACKUP_EXPORT_TAG) {
            busy = true
            scope.launch {
                result = export(outPath.trim(), pass).fold({ pass = ""; pass2 = ""; it }, { it.message ?: "export failed" })
                busy = false
            }
        }
        androidx.compose.material3.HorizontalDivider(color = Palette.Muted.copy(alpha = 0.25f))
        Text("Restore from a backup (merges into the secrets here)", fontSize = 12.sp, color = Palette.Text)
        SecretTextField(inPath, { inPath = it; conflicts = emptyList(); answers = emptyMap() }, "Backup file", false, Modifier.testTag(SECRET_BACKUP_IMPORT_PATH_TAG))
        MaskedInput(inPass, { inPass = it }, "Its passphrase", Modifier.testTag(SECRET_BACKUP_IMPORT_PASS_TAG))
        if (conflicts.isNotEmpty()) {
            Text("These secrets exist here with other values. Keep yours or replace each with the backup's:", fontSize = 11.sp, color = Palette.Warn)
            for (id in conflicts) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("$SECRET_BACKUP_CONFLICT_TAG-$id")) {
                    Text(id, fontFamily = Mono, fontSize = 12.sp, color = Palette.Text, modifier = Modifier.weight(1f))
                    for (choice in listOf("keep", "replace")) {
                        val picked = answers[id] == choice
                        TextButton(onClick = { answers = answers + (id to choice) }, modifier = Modifier.testTag("$SECRET_BACKUP_CONFLICT_TAG-$id-$choice")) {
                            Text(if (choice == "keep") "Keep mine" else "Use backup's", fontSize = 12.sp, color = if (picked) Palette.Info else Palette.Muted)
                        }
                    }
                }
            }
        }
        SecretButton(
            if (conflicts.isEmpty()) "Import backup" else "Apply import", inPath.isNotBlank() && inPass.isNotEmpty() && !busy && conflicts.all { it in answers },
            SECRET_BACKUP_IMPORT_TAG,
        ) {
            busy = true
            scope.launch {
                result = import(inPath.trim(), inPass, answers).fold({ o ->
                    if (!o.applied) {
                        conflicts = o.conflicts
                        "${o.conflicts.size} secret${if (o.conflicts.size == 1) "" else "s"} to answer before anything is imported."
                    } else {
                        conflicts = emptyList(); answers = emptyMap(); inPass = ""
                        o.summary()
                    }
                }, { it.message ?: "import failed" })
                busy = false
            }
        }
        if (result.isNotEmpty()) Text(result, fontFamily = Mono, fontSize = 11.sp, color = Palette.Muted, modifier = Modifier.testTag(SECRET_BACKUP_RESULT_TAG))
    }
}
