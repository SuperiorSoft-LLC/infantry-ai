// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugin.api

import kotlinx.serialization.Serializable

/**
 * UPDATE-ENGINE-1 (docs/wiki/Updates.md): a component's update declaration, the `update` object of a module.json (and the
 * `update.source.*` settings for the app itself). It is data only: WHERE the new versions live. HOW they are fetched is the
 * source [UpdateSourceRef.type], which a daemon plugin registers.
 */
@Serializable
data class UpdateDeclaration(val source: UpdateSourceRef)

/**
 * Where one component's new versions live.
 *
 *  - [type]: the source type (`github`, `folder`, a vendor's own type...): the id a daemon plugin registered.
 *  - [location]: what that type needs to find releases (`owner/repo`, a URL, a folder). Opaque to the engine.
 *  - [channel]: an optional release channel the type understands (`production`, `beta`...).
 *  - [credential]: how to find the secret the source needs (a private repo, a paid vendor's license key). Null = none.
 *    It names a secret; it never holds one.
 */
@Serializable
data class UpdateSourceRef(
    val type: String,
    val location: String = "",
    val channel: String? = null,
    val credential: UpdateCredentialRef? = null,
)

/**
 * The NAMES under which a source's secret is looked up, in this order: the environment variable [env], then the daemon
 * keystore entry [secret]. A secret value is never written in a module.json, a settings file or a repo.
 */
@Serializable
data class UpdateCredentialRef(val env: String? = null, val secret: String? = null) {
    val isEmpty: Boolean get() = env.isNullOrBlank() && secret.isNullOrBlank()
}
