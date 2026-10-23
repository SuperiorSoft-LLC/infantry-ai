package com.superiorsoftllc.agenthq.provider

import com.superiorsoftllc.agenthq.session.InfantryEnv

/**
 * Selects the agent provider from `AGENTHQ_PROVIDER` (default `opencode`, i.e. Phase 1 parity).
 *
 * Dispatch is CONFIGURATION-driven, like `AGENTHQ_RUNNER` for the transport: the string names a
 * provider and a registry resolves it. A `register` seam lets a new provider be wired without the
 * daemon knowing about it (tests use it for a vendor-neutral fake). Switching providers is a pure
 * config change, no recompile.
 *
 * Honest limit (recorded, not hidden): ADDING a brand-new provider kind still needs an
 * [AgentProvider] implementation + a `register` call, i.e. code. A fully data-driven provider whose
 * argv template and output parser live in configuration was deliberately NOT built -- it is a much
 * larger design and was not warranted for two providers.
 *
 * Phase 9 adds `stub` (see [StubProvider]): not a provider kind but a scripted replay of the same
 * seam, so `AGENTHQ_PROVIDER=stub` boots the daemon with no CLI installed. It is deliberately
 * registered HERE rather than special-cased in the daemon, so the daemon's provider handling stays
 * exactly as uniform for the stub as it is for the real CLIs.
 */
object ProviderFactory {
    const val ENV = "AGENTHQ_PROVIDER"
    const val DEFAULT = "opencode"

    private val builders = LinkedHashMap<String, () -> AgentProvider>(
        mapOf(
            // BUG-M2-11b-2: opencode's persistent mode; whether it is used is `session.keepAliveMinutes`'s call (SessionHost).
            "opencode" to { OpenCodeProvider(sessionAdapter = com.superiorsoftllc.agenthq.opencode.OpenCodeServeAdapter()) },
            "claude" to { ClaudeCodeProvider(sessionAdapter = com.superiorsoftllc.agenthq.claude.ClaudeSessionAdapter()) },
            "stub" to { StubProvider(delayMs = stubDelayMs(InfantryEnv.get(STUB_DELAY_ENV))) },
        ),
    )

    /**
     * TEST-ONLY knob: pause (ms) before each scripted stub line. Unset/blank keeps the 25 ms
     * default. Exists so a cancel can land mid-run over a real network (the Phase 7 gate): at 25 ms
     * a four-line script finishes before a CLI `cancel --force` arrives. Stays below the runner's
     * 30 s await when `lines x delay` is under 30 s; a cancel kills the replay either way.
     */
    const val STUB_DELAY_ENV = "AGENTHQ_STUB_DELAY_MS"
    private const val STUB_DEFAULT_DELAY_MS = 25L

    internal fun stubDelayMs(raw: String?): Long {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return STUB_DEFAULT_DELAY_MS
        return text.toLongOrNull()?.takeIf { it >= 0 }
            ?: error("Invalid $STUB_DELAY_ENV='$raw' (want a non-negative integer of milliseconds).")
    }

    /**
     * Aliases accepted for the same provider, so config is forgiving.
     *
     * NOTE: a REGISTERED provider always beats an alias (see [fromFlag]), and the alias table must
     * never claim a name a caller or test might register. `fake` was removed from here for exactly
     * that reason: it collided with `ProviderSeamTest`'s registered vendor-neutral fake.
     */
    private val aliases = mapOf(
        "oc" to "opencode",
        "claude-code" to "claude",
        "claudecode" to "claude",
        "cc" to "claude",
        "script" to "stub",
        "scripted" to "stub",
    )

    fun fromEnv(): AgentProvider = fromFlag(InfantryEnv.get(ENV))

    /** BUG-PERM-4: every name a spawn can resolve to a provider right now (built-ins, then registrations), aliases excluded. */
    internal fun ids(): List<String> = builders.keys.toList()

    /**
     * Resolve a provider by its configured name. Blank/null selects [DEFAULT].
     * Unknown names fail loudly with the known ids, rather than silently falling back.
     */
    fun fromFlag(flag: String?): AgentProvider {
        val raw = flag?.trim()?.lowercase().orEmpty()
        if (raw.isEmpty()) return builders.getValue(DEFAULT).invoke()
        val builder = builderFor(raw) ?: error("Unknown $ENV='$flag' (known: ${builders.keys.sorted()}).")
        return builder()
    }

    /**
     * Contract 1.3.0: the providers a spawn may name BESIDES the default, from `AGENTHQ_PROVIDERS`,
     * a comma list of the names `AGENTHQ_PROVIDER` takes (`claude,stub`). Unset or blank = none, so
     * the daemon serves only its default, exactly as before 1.3.0.
     *
     * Opt-in on purpose: `claude` draws on a paid subscription, so no client can spawn it unless the
     * operator listed it here.
     */
    fun additionalFromEnv(default: AgentProvider): List<AgentProvider> =
        additionalFromFlag(InfantryEnv.get(PROVIDERS_ENV), default)

    const val PROVIDERS_ENV = "AGENTHQ_PROVIDERS"

    /**
     * Pure parse of [additionalFromEnv]. The default's own id is dropped (it is always available),
     * duplicates collapse after alias resolution (`cc,claude` is one entry), and an unknown name fails
     * loudly, naming [PROVIDERS_ENV], rather than being skipped.
     */
    internal fun additionalFromFlag(raw: String?, default: AgentProvider): List<AgentProvider> {
        val seen = mutableSetOf(default.id)
        return raw.orEmpty().split(',')
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .map { name ->
                val builder = builderFor(name)
                    ?: error("Unknown $PROVIDERS_ENV entry '$name' (known: ${builders.keys.sorted()}).")
                builder()
            }
            .filter { seen.add(it.id) }
    }

    /**
     * A REGISTERED name wins over an alias, always. A test (or a future extension point) that
     * registers a provider under a name must get that provider back -- an alias silently stealing
     * the name would return the wrong provider and the failure would look like a bug in the
     * registration instead. This is why no alias is allowed to claim a plausible test name such as
     * `fake`.
     */
    private fun builderFor(name: String): (() -> AgentProvider)? =
        builders[name] ?: aliases[name]?.let { builders[it] }

    /**
     * Extension point: wire another provider by configuration name. Used by tests to register a
     * vendor-neutral fake and prove the seam accepts a provider it has never seen.
     */
    internal fun register(id: String, builder: () -> AgentProvider) {
        builders[id.trim().lowercase()] = builder
    }

    /**
     * PROV-1: wire a config-defined provider ONLY when its name is still free, and report whether it
     * was wired. A built-in (or an earlier registration) always wins, so a file dropped in the
     * provider directory can never silently shadow `opencode`/`claude`/`stub`. Keeping the rule here,
     * at the single place provider names are owned, is what stops a second hard-coded built-in list
     * in the loader from drifting out of sync with [builders].
     */
    internal fun registerIfAbsent(id: String, builder: () -> AgentProvider): Boolean {
        val key = id.trim().lowercase()
        if (key.isEmpty() || builders.containsKey(key)) return false
        builders[key] = builder
        return true
    }
}
