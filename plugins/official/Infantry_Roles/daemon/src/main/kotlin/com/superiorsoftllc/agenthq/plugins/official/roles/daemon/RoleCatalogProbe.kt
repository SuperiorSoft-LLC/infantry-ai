package com.superiorsoftllc.agenthq.plugins.official.roles.daemon


import java.io.File
import java.nio.file.Files

/**
 * A human-checkable dump of the role catalog and the avatar chain -- the same spirit as
 * `probeProvider`: a small runnable that proves the built artifact actually loads its data.
 *
 * `.\gradlew.bat :superiorSoftLibrary:agentHq:roleCatalog` (optionally with a custom-role directory
 * as the one argument, or `AGENTHQ_UI_DATA_DIR` set to look for overrides).
 */
fun main(args: Array<String>) {
    val customDir = args.firstOrNull()?.takeIf { it.isNotBlank() }?.let(::File)
    val catalog = RoleCatalog.load(customDir)

    println("Infantry AI role catalog -- ${catalog.ids().size} roles" + (customDir?.let { " (custom dir: $it)" } ?: ""))
    for (role in catalog.all()) {
        val firstLine = role.persona.lineSequence().firstOrNull { it.isNotBlank() }?.take(64) ?: "(no persona)"
        println("  %-16s %-18s %-8s %-9s %s".format(role.id, role.name, role.color, role.source, firstLine))
    }

    val dataDir = System.getenv("AGENTHQ_UI_DATA_DIR")?.takeIf { it.isNotBlank() }?.let(::File)
        ?: Files.createTempDirectory("agenthq-rolecatalog").toFile()
    val resolver = AvatarResolver.fromClasspath(AvatarOverrides(dataDir), catalog)
    println("avatar chain (client data dir: $dataDir)")
    println("  bundled manifest broken assets: ${resolver.brokenAssets().ifEmpty { listOf("none") }.joinToString(", ")}")
    for (id in listOf("architect", "custom", "brand-new-role")) describe(id, resolver.resolve(id))

    val unknown = catalog.rendered("brand-new-role")
    println("unknown role '${unknown.id}' renders as '${unknown.name}' ${unknown.color} (${unknown.source})")
}

private fun describe(roleId: String, resolved: ResolvedAvatar) = when (resolved) {
    is ResolvedAvatar.Override -> println("  $roleId -> override ${resolved.file.name} (${resolved.mediaType})")
    is ResolvedAvatar.Bundled -> println("  $roleId -> bundled ${resolved.mediaType} (${resolved.bytes.size} bytes)")
    is ResolvedAvatar.Generated -> println("  $roleId -> generated ${resolved.color} '${resolved.initials}'")
}
