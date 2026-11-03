// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon

import com.superiorsoftllc.infantry.daemon.plugins.DaemonApiClassLoader
import com.superiorsoftllc.infantry.harness.HarnessApiClassLoader
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * SDK-SURFACE-1 (docs/PLUGIN-REPO-DESIGN.md 1.2, 1.3): the packages a plugin class loader exposes equal the published SDK.
 *  (a) every exposed `com.superiorsoftllc.infantry.` prefix is a package of a published SDK module's sources;
 *  (b) every package of the daemon API / harness API main sources is covered by an exposed prefix.
 * Third-party prefixes (kotlin., kotlinx.) are not ours and are skipped.
 */
class SdkSurfaceGuardTest {
    private val ours = "com.superiorsoftllc.infantry."

    private fun packages(module: String): Set<String> =
        File(TestDaemonPlugins.repo, "superiorSoftLibrary/$module/src").walkTopDown()
            .filter { it.isFile && it.extension == "kt" && "main" in it.invariantSeparatorsPath.substringAfter("/$module/src/").substringBefore("/").lowercase() }
            .mapNotNull { f -> f.useLines { l -> l.firstOrNull { it.startsWith("package ") } }?.removePrefix("package ")?.trim() }
            .toSet()

    private fun covered(pkg: String, prefixes: List<String>) = prefixes.any { (pkg + ".").startsWith(it) }
    private fun owned(prefix: String, pkgs: Set<String>) = pkgs.any { (it + ".").startsWith(prefix) }

    private fun check(label: String, prefixes: List<String>, sdk: List<String>, mustCover: String) {
        val ourPrefixes = prefixes.filter { it.startsWith(ours) }
        val all = sdk.associateWith { packages(it) }
        assertTrue(all.values.all { it.isNotEmpty() }, "$label: no sources found for $sdk")
        val foreign = ourPrefixes.filter { p -> all.values.none { owned(p, it) } }
        assertTrue(foreign.isEmpty(), "$label: exposed prefixes that are not in a published SDK module ($sdk): $foreign")
        val uncovered = all.getValue(mustCover).filter { !covered(it, prefixes) }
        assertTrue(uncovered.isEmpty(), "$label: $mustCover packages not covered by an exposed prefix (plugins cannot load them): $uncovered")
    }

    @Test fun `daemon class loader exposes exactly the published daemon SDK`() =
        check("daemon", DaemonApiClassLoader.EXPOSED_PREFIXES, listOf("infantryDaemonApi", "infantryPlugins"), "infantryDaemonApi")

    @Test fun `harness class loader exposes exactly the published harness SDK`() =
        check("harness", HarnessApiClassLoader.EXPOSED_PREFIXES, listOf("infantryHarnessApi"), "infantryHarnessApi")
}
