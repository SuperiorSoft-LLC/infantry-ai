// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.claude.harness

import com.superiorsoftllc.infantry.harness.api.HarnessAnswer
import com.superiorsoftllc.infantry.harness.api.HarnessQuestion
import com.superiorsoftllc.infantry.harness.api.McpListing
import com.superiorsoftllc.infantry.harness.api.McpPlan
import com.superiorsoftllc.infantry.harness.api.McpServerSpec
import com.superiorsoftllc.infantry.harness.api.McpServerState
import com.superiorsoftllc.infantry.harness.api.McpServerStatus
import com.superiorsoftllc.infantry.harness.api.McpSource
import com.superiorsoftllc.infantry.harness.api.McpTransport

/**
 * BROWSER-OWNER-1: Claude in Chrome (the owner's real Chrome and sign-ins) as capability `browser.owner`, Claude harness only.
 * It is listed as a virtual server [SERVER] that is never written into the MCP config; when a grant puts it in the session's plan the
 * launch gets `--chrome`, but only after the owner confirms he is present. Default browser stays Infantry_Browser's Playwright.
 */
object ClaudeChrome {
    const val SERVER = "claude-in-chrome"
    const val CAPABILITY = "browser.owner"
    const val FLAG = "--chrome"
    const val NOT_CONNECTED = "connect the Claude extension in Chrome"
    const val YES = "I am at the PC: allow Claude in Chrome"
    const val NO = "No, do not start"
    private const val HOST_NAME = "com.anthropic.claude_code_browser_extension"
    const val HOST_KEY = "HKCU\\Software\\Google\\Chrome\\NativeMessagingHosts\\$HOST_NAME"

    fun isChrome(spec: McpServerSpec): Boolean = spec.name == SERVER

    /** The catalog entry: not connected (extension missing) reads FAILED, so MCP-FAIL-1's preflight refuses with [NOT_CONNECTED]. */
    fun listing(connected: Boolean): McpListing {
        val spec = McpServerSpec(SERVER, McpTransport.Stdio(listOf("claude", FLAG)), McpSource.HARNESS, provides = setOf(CAPABILITY), localOnly = true)
        val status = if (connected) McpServerStatus(SERVER, McpServerState.CONNECTED) else McpServerStatus(SERVER, McpServerState.FAILED, NOT_CONNECTED)
        return McpListing(spec, status)
    }

    /** Whether the plan carries a granted Claude in Chrome. */
    fun wanted(plan: McpPlan): Boolean = plan.delivered.any(::isChrome)

    /** The native-messaging host of the extension is registered (Windows registry / macOS and Linux manifest file). */
    fun extensionConnected(windows: Boolean, regQuery: (String) -> Boolean, fileExists: (String) -> Boolean, home: String): Boolean =
        if (windows) regQuery(HOST_KEY)
        else listOf(
            "$home/Library/Application Support/Google/Chrome/NativeMessagingHosts/$HOST_NAME.json",
            "$home/.config/google-chrome/NativeMessagingHosts/$HOST_NAME.json",
        ).any(fileExists)

    fun question(): HarnessQuestion = HarnessQuestion(
        question = "This ticket asks for Claude in Chrome: the agent will act in YOUR Chrome with your sign-ins. Are you at the PC watching it? You do every sign-in, payment and secret yourself.",
        options = listOf(YES, NO),
        recommended = NO,
        timeoutMillis = null,
    )

    /**
     * True when the launch of [sessionId] must carry [FLAG]. A plan with no granted chrome = false and nothing is asked.
     * Granted but the owner does not answer [YES] = throws, so the start is refused (never a silent launch without it).
     * A yes is remembered per session in [confirmed] (a restart or resume does not ask again).
     */
    fun chromeFor(plan: McpPlan, sessionId: String?, confirmed: MutableSet<String>, ask: (HarnessQuestion) -> HarnessAnswer): Boolean {
        if (!wanted(plan)) return false
        val key = sessionId.orEmpty()
        if (key in confirmed) return true
        val answer = ask(question())
        check(answer.answered && YES in answer.answers) { "start refused: Claude in Chrome needs the owner's present-at-the-PC confirmation (not given)" }
        confirmed += key
        return true
    }
}
