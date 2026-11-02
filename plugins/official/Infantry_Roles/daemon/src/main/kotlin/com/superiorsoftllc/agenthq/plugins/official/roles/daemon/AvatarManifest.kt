package com.superiorsoftllc.agenthq.plugins.official.roles.daemon

import com.superiorsoftllc.agenthq.protocol.ImageFormat

import com.superiorsoftllc.agenthq.protocol.RoleIds

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * One bundled avatar: the **file** (relative to `/avatars/`) plus its provenance.
 *
 * [source] and [licence] exist so the licence question is answered by **audit** rather than by
 * archaeology through binaries: whichever art a bundle ships, its origin and terms are fields in
 * the manifest. Neither field is used at runtime; both are required to be *recordable*, which is the
 * whole point of putting them here instead of in a commit message.
 */
@Serializable
data class AvatarAsset(
    val file: String,
    val source: String? = null,
    val licence: String? = null,
)

/**
 * The bundled avatar manifest, `/avatars/index.json`: a bare JSON object mapping **role id -> asset**.
 *
 * It is enumerable data, deliberately: the shipped set is swapped by editing this file and its
 * assets, never by editing Kotlin. There is no `when (roleId)` anywhere in this package.
 *
 * An **empty manifest is legal**: `{}` (or no manifest at all) yields [EMPTY], and resolution falls
 * straight through to the generated badge. The product must work with zero bundled art.
 */
class AvatarManifest private constructor(val assets: Map<String, AvatarAsset>) {
    fun asset(roleId: String): AvatarAsset? = assets[roleId]

    val isEmpty: Boolean get() = assets.isEmpty()

    companion object {
        /** Classpath path of the bundled manifest. */
        const val PATH: String = "/avatars/index.json"

        /** Resource directory assets' [AvatarAsset.file] are relative to. */
        const val DIR: String = "/avatars/"

        val EMPTY: AvatarManifest = AvatarManifest(emptyMap())

        private val json = Json { ignoreUnknownKeys = true }
        private val mapSerializer = MapSerializer(String.serializer(), AvatarAsset.serializer())

        /**
         * Parse a manifest body. `{}` and blank text are the empty manifest. A key that is not a
         * legal role id, or an asset with no file, is a broken bundle and throws -- a typo that
         * silently disabled an avatar is worse than a load failure an operator can see.
         */
        fun parse(text: String): AvatarManifest {
            val body = text.removePrefix(Char(0xFEFF).toString()).trim()
            if (body.isEmpty() || body == "{}") return EMPTY
            val decoded = try {
                json.decodeFromString(mapSerializer, body)
            } catch (e: Exception) {
                throw IllegalArgumentException("$PATH is not a valid avatar manifest: ${e.message}", e)
            }
            decoded.forEach { (roleId, asset) ->
                require(RoleIds.isValid(roleId)) {
                    "$PATH maps role id '$roleId': a role id is a lowercase letter then up to 31 lowercase letters, digits or '-'"
                }
                require(asset.file.isNotBlank()) { "$PATH: role '$roleId' has no 'file'" }
                require(ImageFormat.fromFileName(asset.file) != null) {
                    "$PATH: role '$roleId' names '${asset.file}': only .png and .webp assets are supported"
                }
            }
            return AvatarManifest(decoded)
        }

        /**
         * Read the manifest at [path] through [readText] (null = manifest absent = empty). A
         * manifest that exists but does not parse throws; a caller that wants to degrade instead
         * catches and uses [EMPTY].
         */
        fun read(readText: (String) -> String?, path: String = PATH): AvatarManifest =
            readText(path)?.let(::parse) ?: EMPTY
    }
}
