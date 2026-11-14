// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.model

import com.superiorsoftllc.infantry.ui.protocol.HarnessInstallReport
import com.superiorsoftllc.infantry.ui.protocol.HarnessInstallStatus
import com.superiorsoftllc.infantry.ui.protocol.ProviderInfo
import com.superiorsoftllc.infantry.ui.protocol.ProviderModels
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

    /**
     * The free models [id] offers: from the `models` frame ([catalog], where the model ids live), the provider's own list and its
     * default model (opencode's default is a free one).
     */
    fun freeModels(id: String, providers: List<ProviderInfo>, catalog: List<ProviderModels> = emptyList()): List<String> {
        val p = providers.firstOrNull { it.id == id }
        return (listOfNotNull(p?.defaultModel) + p?.models.orEmpty() + catalog.filter { it.provider == id }.flatMap { it.models })
            .filter { it.contains("free", ignoreCase = true) }.distinct()
    }

    /** True when [id] can answer without asking the user for anything more. */
    fun credentialOk(id: String, secrets: List<SecretRow>, providers: List<ProviderInfo>, catalog: List<ProviderModels> = emptyList()): Boolean {
        val row = keyRow(id, secrets) ?: return true
        return row.set || row.signedIn == "true" || row.loginState == LOGIN_DONE || row.usesSecret.isNotBlank() || freeModels(id, providers, catalog).isNotEmpty()
    }

    /** The harness ids that can answer now. */
    fun talkable(report: HarnessInstallReport, secrets: List<SecretRow>, providers: List<ProviderInfo>, catalog: List<ProviderModels> = emptyList()): List<String> =
        report.harnesses.filter { installed(it) && credentialOk(it.id, secrets, providers, catalog) }.map { it.id }

    /** Whether the setup must open by itself: null while the daemon has not answered yet (never open on a guess). */
    fun needed(report: HarnessInstallReport?, secrets: List<SecretRow>?, providers: List<ProviderInfo>, catalog: List<ProviderModels> = emptyList()): Boolean? {
        if (report == null || secrets == null) return null
        if (report.harnesses.isEmpty()) return null
        return talkable(report, secrets, providers, catalog).isEmpty()
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
    fun firstModel(id: String, secrets: List<SecretRow>, providers: List<ProviderInfo>, catalog: List<ProviderModels> = emptyList()): String? {
        val provider = providers.firstOrNull { it.id == id } ?: return null
        val row = keyRow(id, secrets)
        val hasCredential = row == null || row.set || row.signedIn == "true" || row.loginState == LOGIN_DONE || row.usesSecret.isNotBlank()
        if (!hasCredential) freeModels(id, providers, catalog).firstOrNull()?.let { return it }
        return provider.defaultModel ?: provider.models?.firstOrNull()
    }

    /** ONBOARD-1's in-app sign-in finished (`SecretRow.loginState`); the probe's signedIn may lag behind it. */
    private const val LOGIN_DONE = "done"

    /** The project the first agent runs in when the machine has none yet. */
    const val STARTER_PROJECT = "My first project"

    /** The first agent's task: short, so the reply arrives fast and costs next to nothing. */
    const val HELLO_PROMPT = "Say hello in one short sentence and tell me you are ready to help."
}
