package com.superiorsoftllc.agenthq.provider

import com.superiorsoftllc.agenthq.runner.ProcessSpec

/**
 * PROV-1: a provider whose argv comes from a JSON config file, not compiled-in code.
 *
 * The point of the feature is that adding a provider is a DATA change -- drop a definition file and
 * the seam resolves it -- so an operator can support a CLI we never anticipated without a recompile.
 * It sits BESIDE the built-ins, behind the same [AgentProvider] seam: nothing above it knows whether
 * `aider` is compiled in or loaded from disk.
 *
 * PROV-2 makes [args] a small TEMPLATE rather than literals: an arg that is exactly `{model}`,
 * `{session}`, `{workdir}` or `{prompt}` is replaced by the [RunRequest]'s matching value, so one
 * config file serves fresh and resume runs without a recompile. A placeholder whose value is absent
 * (null/blank) is dropped TOGETHER WITH its preceding switch token, so an unset `{session}` removes
 * `--session {session}` as a pair rather than leaving a dangling `--session` that a real CLI would
 * read the next argument as -- that drop rule is the whole reason a template is usable on a CLI.
 * Anything that is not a lone placeholder (a literal token, or `--flag=x` with no placeholder) is
 * passed through untouched, and an unknown `{name}` is kept verbatim rather than silently eaten.
 *
 * Still deferred, and stated so it is not mistaken for an oversight: per-file malformed-input
 * handling is PROV-3, and a configurable session-id regex is PROV-4.
 */
class ConfigProvider(
    override val id: String,
    private val command: String,
    private val args: List<String> = emptyList(),
    private val sessionIdRegex: Regex? = null,
) : AgentProvider {

    override fun spec(request: RunRequest): ProcessSpec = ProcessSpec(
        command = listOf(command) + fill(args, request),
        workingDir = request.workingDir,
    )

    override fun sessionIdFromLine(line: String): String? {
        if (sessionIdRegex == null) return null
        val match = sessionIdRegex.find(line) ?: return null
        return if (match.groupValues.size > 1) match.groupValues[1] else match.value
    }

    /**
     * Resolve placeholders in [args] against [request]. Built left-to-right so the "drop the switch
     * with its missing value" rule can look at the token already emitted.
     */
    private fun fill(args: List<String>, request: RunRequest): List<String> {
        val out = ArrayList<String>(args.size)
        args.forEach { arg ->
            val name = PLACEHOLDER.matchEntire(arg)?.groupValues?.get(1)
            if (name == null || name !in KNOWN) {
                out.add(arg)
                return@forEach
            }

            val value = when (name) {
                "model" -> request.model
                "session" -> request.resumeSessionId
                "workdir" -> request.workingDir?.path
                else -> request.prompt
            }

            if (value.isNullOrBlank()) {
                if (out.isNotEmpty() && isSwitch(out.last())) out.removeAt(out.lastIndex)
            } else {
                out.add(value)
            }
        }
        return out
    }

    /** A bare CLI switch (`-m`, `--session`), never a lone `-` (the stdin value) or a placeholder. */
    private fun isSwitch(token: String): Boolean =
        token.startsWith("-") && token != "-" && PLACEHOLDER.matchEntire(token) == null

    private companion object {
        val PLACEHOLDER = Regex("""\{([A-Za-z][A-Za-z0-9_]*)\}""")
        val KNOWN = setOf("model", "session", "workdir", "prompt")
    }
}
