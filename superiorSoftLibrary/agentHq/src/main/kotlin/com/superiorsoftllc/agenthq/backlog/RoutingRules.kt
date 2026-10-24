package com.superiorsoftllc.agenthq.backlog

import com.superiorsoftllc.agenthq.protocol.RoutingRule
import com.superiorsoftllc.agenthq.protocol.TicketType
import com.superiorsoftllc.agenthq.protocol.TicketWeight

/**
 * ROUTING-1 (contract 1.128.0): a project's task routing rules -- which role / provider / model a ticket starts on, by its
 * weight, type and role. Pure: matching, shape checks and the Opus safety line live here; the executor check (does the
 * harness list this provider / model) is passed in by the caller, so the one check ticket create uses is reused.
 *
 * Precedence at start (TicketBoard): a ticket that pins its own provider or model never reaches the rules; otherwise, in
 * a project that HAS rules, the first matching rule decides (it beats the profile / role binding and
 * `tickets.weightExecutors`). A rule whose executor is refused at start parks the ticket: it is NEVER retried on another
 * rule, the default row, or another harness.
 */
object RoutingRules {
    const val MAX_RULES = 32
    const val MAX_NAME = 60

    /** The Opus safety line: Opus runs only through pi, as this exact model id. */
    const val OPUS_PROVIDER = "pi"
    const val OPUS_MODEL = "anthropic/claude-opus-5-5"

    /** A rule with no match field: it matches every ticket. */
    fun isDefault(rule: RoutingRule): Boolean = rule.weights.isEmpty() && rule.ticketTypes.isEmpty() && rule.role == null

    /** The first rule of [rules] matching a ticket of [weight] / [ticketType] / [role], or null. */
    fun match(rules: List<RoutingRule>, weight: Int?, ticketType: String?, role: String?): RoutingRule? =
        rules.firstOrNull { matches(it, weight, ticketType, role) }

    fun matches(rule: RoutingRule, weight: Int?, ticketType: String?, role: String?): Boolean =
        (rule.weights.isEmpty() || (weight != null && weight in rule.weights)) &&
            (rule.ticketTypes.isEmpty() || (ticketType != null && TicketType.normalize(ticketType.lowercase()) in rule.ticketTypes)) &&
            (rule.role == null || rule.role == role)

    /**
     * Refuses (IllegalArgumentException) a model that names Opus on any provider but [OPUS_PROVIDER] with [OPUS_MODEL].
     * Applied when rules are saved AND at every rule-driven start.
     */
    fun requireOpusSafe(ruleName: String, provider: String?, model: String?) {
        val m = model?.trim().orEmpty()
        if (!m.contains("opus", ignoreCase = true)) return
        require(provider?.trim()?.lowercase() == OPUS_PROVIDER && m == OPUS_MODEL) {
            "rule '$ruleName': Opus runs only as provider '$OPUS_PROVIDER' with model '$OPUS_MODEL' (was '${provider ?: "-"}/${m.take(80)}')"
        }
    }

    /**
     * Normalises and checks a whole rule list; throws IllegalArgumentException naming the first problem (nothing is
     * stored then). [checkExecutor] is the harness check (provider resolves, model listed, effort known); [roleKnown]
     * says whether a role id exists. An empty list is valid (it clears the rules).
     */
    fun validate(
        rules: List<RoutingRule>,
        checkExecutor: (provider: String, model: String, effort: String?) -> Unit,
        roleKnown: (String) -> Boolean,
    ): List<RoutingRule> {
        if (rules.isEmpty()) return emptyList()
        require(rules.size <= MAX_RULES) { "at most $MAX_RULES rules; got ${rules.size}" }
        val names = HashSet<String>()
        val out = rules.mapIndexed { index, raw ->
            val name = raw.name.trim()
            require(name.isNotEmpty()) { "rule ${index + 1} has no name" }
            require(name.length <= MAX_NAME) { "rule '${name.take(40)}': name is longer than $MAX_NAME characters" }
            require(name.none { it.isISOControl() }) { "rule '${name.take(40)}': name contains a control character" }
            require(names.add(name.lowercase())) { "rule name '$name' is used twice" }
            val weights = raw.weights.distinct().sorted()
            weights.firstOrNull { it !in TicketWeight.RANGE }?.let {
                throw IllegalArgumentException("rule '$name': weight $it is not in ${TicketWeight.RANGE}")
            }
            val types = raw.ticketTypes.map { TicketType.normalize(it.trim().lowercase()) }.distinct()
            types.firstOrNull { it !in TicketType.ALL }?.let {
                throw IllegalArgumentException("rule '$name': unknown ticket type '${it.take(40)}' (one of ${TicketType.ALL.joinToString()})")
            }
            val role = raw.role?.trim()?.takeIf { it.isNotEmpty() }
            val setRole = raw.setRole?.trim()?.takeIf { it.isNotEmpty() }
            role?.let { require(roleKnown(it)) { "rule '$name': unknown role '${it.take(40)}'" } }
            setRole?.let { require(roleKnown(it)) { "rule '$name': unknown setRole '${it.take(40)}'" } }
            val provider = raw.provider.trim().lowercase()
            val model = raw.model.trim()
            require(provider.isNotEmpty()) { "rule '$name' needs a provider" }
            require(model.isNotEmpty()) { "rule '$name' needs a model" }
            val effort = raw.effort?.trim()?.takeIf { it.isNotEmpty() }
            requireOpusSafe(name, provider, model)
            try {
                checkExecutor(provider, model, effort)
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("rule '$name': ${e.message}")
            }
            RoutingRule(name, weights, types, role, provider, model, effort, setRole)
        }
        require(isDefault(out.last())) {
            "the last rule must be the default row (no weights, no ticketTypes, no role), so every ticket matches one; '${out.last().name}' is not"
        }
        out.dropLast(1).firstOrNull(::isDefault)?.let {
            throw IllegalArgumentException("rule '${it.name}' matches every ticket, so the rules after it never apply: only the last rule may be the default row")
        }
        return out
    }

    /** "1-2", "3", "4-5", "1,3" -- a short label of [weights] for the CLI and logs; "any" when empty. */
    fun weightLabel(weights: List<Int>): String {
        if (weights.isEmpty()) return "any"
        val sorted = weights.sorted()
        return if (sorted.size > 1 && sorted.zipWithNext().all { (a, b) -> b == a + 1 }) "${sorted.first()}-${sorted.last()}" else sorted.joinToString(",")
    }
}
