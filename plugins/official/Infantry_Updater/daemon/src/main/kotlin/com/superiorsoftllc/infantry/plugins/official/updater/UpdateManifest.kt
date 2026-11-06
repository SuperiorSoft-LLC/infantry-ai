// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.updater

import java.util.Properties

/**
 * UPDATER-DEFAULT-1: where this app's releases come from, as MANIFEST data: the `appUpdate` block of the Updater's
 * module.json (`{"type":"github","repo":"owner/repo","visibility":"public","channel":"production","checkIntervalHours":24}`),
 * which daemon/build.gradle.kts bakes into the jar as [RESOURCE] (a build may override a value with
 * `-PinfantryUpdateRepo` / `-PinfantryUpdateVisibility` / `-PinfantryUpdateChannel`; the release workflow passes its own
 * repository). It is the lowest layer of [UpdateSettings.resolve]: developer overrides (env, `update.properties`, hidden
 * module config) still win. The owner's rule: an end user never has to tell the app where its updates are.
 */
object UpdateManifest {
    const val RESOURCE = "infantry-update-manifest.properties"

    const val KEY_TYPE = "type"
    const val KEY_REPO = "repo"
    const val KEY_VISIBILITY = "visibility"
    const val KEY_CHANNEL = "channel"
    const val KEY_INTERVAL = "checkIntervalHours"

    /** NIGHTLY-1: `channels=<id>,<id>` and `channels.<id>.{label,release,preRelease}` ([ReleaseChannel.fromManifest]). */
    const val KEY_CHANNELS = "channels"

    /** NIGHTLY-1: the channel an install follows until the user picks another (`updater.releaseChannel`). */
    const val KEY_DEFAULT_CHANNEL = "defaultChannel"

    private val values: Properties by lazy {
        load(runCatching { UpdateManifest::class.java.classLoader?.getResourceAsStream(RESOURCE)?.reader()?.use { it.readText() } }.getOrNull())
    }

    /** Parses the resource text (null = no resource: every value unset). */
    fun load(text: String?): Properties = Properties().apply { if (text != null) runCatching { load(text.reader()) } }

    /** The manifest's value for [key]; blank = unset. */
    fun get(key: String): String? = values.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() }
}
