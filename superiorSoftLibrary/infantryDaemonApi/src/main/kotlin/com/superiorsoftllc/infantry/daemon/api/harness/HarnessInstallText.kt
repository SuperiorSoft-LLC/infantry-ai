package com.superiorsoftllc.infantry.daemon.api.harness

/** RUNTIME-LOAD-1: the printed tables of `harness-status` / `harness-install`, shared by the CLI and the Infantry_Agents tools. */
object HarnessInstallText {
    const val SIGN_IN =
        "Sign-in is separate: each CLI keeps its own login (run `claude` once, `opencode auth login`, or set the provider's " +
            "API-key environment variable). Infantry AI stores no key."

    fun render(report: HarnessInstallReport, out: Appendable) {
        out.appendLine("id | state | version | where | package")
        for (h in report.harnesses) {
            out.appendLine(
                buildString {
                    append(h.id).append(" | ").append(h.state).append(" | ").append(h.installedVersion ?: "-")
                    append(" | ").append(h.path?.let { if (h.managed) "$it (app-managed)" else it } ?: "-")
                    append(" | ").append(h.npmPackage)
                    h.wanted?.let { append(" | pinned: ").append(it) }
                    h.latest?.let { append(" | latest: ").append(it) }
                },
            )
        }
        out.appendLine("install dir: ${report.prefixDir}")
        out.appendLine(report.npmPath?.let { "npm: $it" } ?: "npm: NOT FOUND")
        report.guidance?.let { out.appendLine(it) }
        out.appendLine(SIGN_IN)
    }

    fun render(result: HarnessInstallResult, out: Appendable) {
        for (line in result.log) out.appendLine(line)
        for (o in result.outcomes) {
            out.appendLine("${o.id}: ${o.result}" + (o.version?.let { " $it" } ?: "") + (o.message?.let { " - $it" } ?: ""))
        }
        if (result.outcomes.isEmpty()) out.appendLine("nothing to install: every harness is already installed")
        render(result.report, out)
    }

    fun failed(result: HarnessInstallResult): Boolean = result.outcomes.any { it.result == "failed" }
}
