package com.superiorsoftllc.infantry.plugins.official.opencode.harness

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File

/** What reading one config file produced: its object, or the reason there is none. */
internal sealed interface ConfigFile {
    /** The file does not exist. Normal: most config files are optional. */
    data object Absent : ConfigFile

    /**
     * The file exists but could not be read or parsed. NEVER silent: callers surface [reason].
     * [reason] is a FIXED phrase ([Reason]) and never text derived from the file: a config file can hold
     * API keys, and a parser's message quotes the input it choked on.
     */
    data class Unreadable(val file: File, val reason: Reason) : ConfigFile

    enum class Reason(val text: String) {
        NOT_JSON("not valid JSON"),
        NOT_AN_OBJECT("top level is not a JSON object"),
        IO("could not be read"),
    }

    data class Parsed(val file: File, val root: JsonObject) : ConfigFile
}

/**
 * JSON-with-comments reader for the CLI config files we read (opencode's `opencode.jsonc`, Claude's
 * `settings.json`). Comments and trailing commas are accepted; anything else malformed is reported
 * as [ConfigFile.Unreadable] with a fixed reason (never the parser's message), so a broken config is a visible note and not
 * a catalogue that quietly shrank.
 */
@OptIn(ExperimentalSerializationApi::class)
internal object Jsonc {
    private val json = Json {
        allowComments = true
        allowTrailingComma = true
    }

    fun read(file: File): ConfigFile {
        if (!file.isFile) return ConfigFile.Absent
        return try {
            val text = file.readText(Charsets.UTF_8).removePrefix("﻿")
            val root = json.parseToJsonElement(text) as? JsonObject
                ?: return ConfigFile.Unreadable(file, ConfigFile.Reason.NOT_AN_OBJECT)
            ConfigFile.Parsed(file, root)
        } catch (e: java.io.IOException) {
            ConfigFile.Unreadable(file, ConfigFile.Reason.IO)
        } catch (e: Exception) {
            // Deliberately ignores e.message: it quotes the file's content (see [ConfigFile.Unreadable]).
            ConfigFile.Unreadable(file, ConfigFile.Reason.NOT_JSON)
        }
    }

    /** `lastModified:length` of every file, "-" for a missing one: changes whenever a source file does. */
    fun fingerprint(files: List<File>): String =
        files.joinToString("|") { if (it.isFile) "${it.lastModified()}:${it.length()}" else "-" }
}

internal fun JsonElement?.asObject(): JsonObject? = this as? JsonObject

internal fun JsonElement?.asString(): String? =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

/** The non-blank strings of a JSON array; null when [this] is not an array. */
internal fun JsonElement?.asStringList(): List<String>? =
    (this as? JsonArray)?.mapNotNull { it.asString() }
