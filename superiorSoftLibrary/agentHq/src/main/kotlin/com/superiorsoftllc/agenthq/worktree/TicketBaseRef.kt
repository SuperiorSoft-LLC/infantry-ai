package com.superiorsoftllc.agenthq.worktree

/**
 * BASE-1a: what a base ref is asked FOR -- the ticket's place on the board. [epicId] is carried now so BASE-1c
 * (a per-epic base overriding the project's) is one more layer in [TicketBaseRef.layered], not a new signature.
 */
data class BaseScope(val projectId: String? = null, val epicId: String? = null)

/** BASE-1c: the ref a start cuts from, and the setting that chose it ([source], e.g. `epic epic-1a2b3c4d`; null = unlabelled). */
data class BaseChoice(val ref: String, val source: String? = null)

/**
 * BUG-REGRESS-1: the ONE place that decides which ref a ticket start cuts its branch from.
 *
 * A start cuts from that ref's COMMIT ([TicketBranches]), never from a checkout's working state, so uncommitted
 * changes in any checkout never reach the ticket's branch and never refuse its start.
 *
 * BASE-1c: precedence is the ticket's EPIC base branch (set with `epic_base`), else its PROJECT base branch (set
 * with `project_base`, BASE-1a), else the daemon's `AGENTHQ_BASE_BRANCH`, else `HEAD` (the commit the repository's
 * main checkout has checked out). The daemon builds that chain with [epicFirst] over [projectFirst] over [fromEnv];
 * nothing else resolves a base.
 *
 * BUG-BASE-087: the DAEMON's chain ends in [NONE], not `HEAD`: whatever the main checkout happens to have checked out
 * is an arbitrary base (it cut tickets from the PM's WIP branch), so a start with nothing configured is refused. Each
 * configured layer labels its choice, so a start's note names the setting that chose the branch.
 */
fun interface TicketBaseRef {

    /** The ref (a branch, a tag, a commit, or `HEAD`) a start of a ticket at [scope] cuts from. */
    fun refFor(scope: BaseScope): String

    /** BASE-1c: [refFor] plus the setting that chose it, for the start's note. Unlabelled unless a layer names itself. */
    fun choose(scope: BaseScope): BaseChoice = BaseChoice(refFor(scope))

    companion object {
        const val ENV = "AGENTHQ_BASE_BRANCH"
        const val HEAD = "HEAD"

        /** BUG-BASE-087: no base configured anywhere; a fresh cut is refused rather than guessed. */
        const val NONE = ""

        /** BUG-BASE-087: the label of a project-base choice, shown in the start's note. */
        const val PROJECT_LABEL = "project base"

        /** The same [ref] for every ticket. */
        fun fixed(ref: String): TicketBaseRef = TicketBaseRef { ref }

        /**
         * `AGENTHQ_BASE_BRANCH` when it is set and not blank (labelled with its name), else [orElse] (`HEAD`; the
         * daemon passes [NONE]), for every ticket.
         */
        fun fromEnv(orElse: String = HEAD, getenv: (String) -> String? = System::getenv): TicketBaseRef =
            layered({ getenv(ENV) }, fixed(orElse), label = { ENV })

        /**
         * BASE-1a: [setting]'s ref for the scope when it names one (null or blank = no setting), else [fallback]'s.
         * BASE-1c: [label] names the source of a ref this layer chose (null = unlabelled); a fallback's choice keeps
         * the fallback's own label.
         */
        fun layered(
            setting: (BaseScope) -> String?,
            fallback: TicketBaseRef,
            label: ((BaseScope) -> String)? = null,
        ): TicketBaseRef = object : TicketBaseRef {
            override fun refFor(scope: BaseScope): String = choose(scope).ref

            override fun choose(scope: BaseScope): BaseChoice {
                val own = setting(scope)?.trim()?.takeIf { it.isNotEmpty() } ?: return fallback.choose(scope)
                return BaseChoice(own, label?.invoke(scope))
            }
        }

        /** BASE-1a: the project's own base branch, then [fallback] (the daemon's env-or-HEAD). */
        fun projectFirst(projectBase: (String) -> String?, fallback: TicketBaseRef): TicketBaseRef =
            layered({ scope -> scope.projectId?.let(projectBase) }, fallback, label = { PROJECT_LABEL })

        /** BASE-1c: the ticket's epic's own base branch, then [fallback] (the project's, then env-or-HEAD). */
        fun epicFirst(epicBase: (String) -> String?, fallback: TicketBaseRef): TicketBaseRef =
            layered({ scope -> scope.epicId?.let(epicBase) }, fallback, label = { scope -> "epic ${scope.epicId}" })
    }
}
