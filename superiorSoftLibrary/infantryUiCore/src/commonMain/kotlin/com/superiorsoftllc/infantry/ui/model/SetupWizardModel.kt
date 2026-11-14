// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.model

import com.superiorsoftllc.infantry.ui.protocol.HarnessInstallReport
import com.superiorsoftllc.infantry.ui.protocol.HarnessInstallStatus
import com.superiorsoftllc.infantry.ui.protocol.ProviderInfo
import com.superiorsoftllc.infantry.ui.protocol.SecretRow

/**
 * ONBOARD-2: the decisions of the guided setup, kept out of the composable so they are tested on their own. Every input is
 * the DAEMON's own answer (its install report, its secret rows, its provider list); nothing here names a harness: the
 * harnesses are whatever the daemon's installer reports, and a credential is whatever a harness declares.
 *
 * "Something to talk to" = at least one harness whose CLI is installed AND that can run: its primary key
 * (`harness.<id>.key`) is set or shared, its subscription sign-in is confirmed, it declares no key at all, or it offers
 * free models (a model id containing "free", e.g. opencode's).
 */
object SetupWizard {
    enum class Step(val title: String) {
        NODE("Get Node.js"),
        HARNESS("Pick your agent tool"),
        SIGN_IN("Sign in"),
        FIRST_AGENT("Say hello"),
    }

    fun installed(s: HarnessInstallStatus): Boolean = s.path != null && s.state != "missing"

    fun keyRow(id: String, secrets: List<SecretRow>): SecretRow? = secrets.firstOrNull { it.name == "harness.$id.key" }

    fun freeModels(id: String, providers: List<ProviderInfo>): List<String> =
        providers.firstOrNull { it.id == id }?.models.orEmpty().filter { it.contains("free", ignoreCase = true) }

    /** True when [id] can answer without asking the user for anything more. */
    fun credentialOk(id: String, secrets: List<SecretRow>, providers: List<ProviderInfo>): Boolean {
        val row = keyRow(id, secrets) ?: return true
        return row.set || row.signedIn == "true" || row.loginState == LOGIN_DONE || row.usesSecret.isNotBlank() || freeModels(id, providers).isNotEmpty()
    }

    /** The harness ids that can answer now. */
    fun talkable(report: HarnessInstallReport, secrets: List<SecretRow>, providers: List<ProviderInfo>): List<String> =
        report.harnesses.filter { installed(it) && credentialOk(it.id, secrets, providers) }.map { it.id }

    /** Whether the setup must open by itself: null while the daemon has not answered yet (never open on a guess). */
    fun needed(report: HarnessInstallReport?, secrets: List<SecretRow>?, providers: List<ProviderInfo>): Boolean? {
        if (report == null || secrets == null) return null
        if (report.harnesses.isEmpty()) return null
        return talkable(report, secrets, providers).isEmpty()
    }

    /** Where the setup starts: Node only while npm is missing and nothing is installed yet; then the pick; then sign-in. */
    fun firstStep(report: HarnessInstallReport): Step = when {
        report.harnesses.none(::installed) && report.npmPath == null -> Step.NODE
        report.harnesses.none(::installed) -> Step.HARNESS
        else -> Step.SIGN_IN
    }

    /** The harness the pick screen starts on: one already installed, else the first the daemon lists. */
    fun defaultPick(report: HarnessInstallReport): String? = (report.harnesses.firstOrNull(::installed) ?: report.harnesses.firstOrNull())?.id

    /**
     * The model of the first agent: a free model when the harness has no credential set (so it answers at once), else the
     * provider's default; null = let the daemon pick the provider's default.
     */
    fun firstModel(id: String, secrets: List<SecretRow>, providers: List<ProviderInfo>): String? {
        val provider = providers.firstOrNull { it.id == id } ?: return null
        val row = keyRow(id, secrets)
        val hasCredential = row == null || row.set || row.signedIn == "true" || row.loginState == LOGIN_DONE || row.usesSecret.isNotBlank()
        if (!hasCredential) freeModels(id, providers).firstOrNull()?.let { return it }
        return provider.defaultModel ?: provider.models?.firstOrNull()
    }

    /** ONBOARD-1's in-app sign-in finished (`SecretRow.loginState`); the probe's signedIn may lag behind it. */
    private const val LOGIN_DONE = "done"

    /** The first agent's task: short, so the reply arrives fast and costs next to nothing. */
    const val HELLO_PROMPT = "Say hello in one short sentence and tell me you are ready to help."
}
