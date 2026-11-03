// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.agents

import com.superiorsoftllc.infantry.protocol.SkillView
import com.superiorsoftllc.infantry.session.RoleInstance

/**
 * P10-K15 (docs/INFANTRY-PHASE10-KNOWLEDGE.md K-D10): the hard ceilings of a first turn's `skills` section. [bodyChars]
 * bounds the attached skills inlined in full (sum of each heading + description + body); [indexLines] / [indexChars] bound every one-line
 * reference (a degraded or deleted attached skill, then the library index). The section therefore never carries more
 * than `bodyChars + indexChars` of skill text plus its fixed wording.
 */
data class SkillBudgets(val bodyChars: Int = 24_000, val indexLines: Int = 40, val indexChars: Int = 6_000)

/** P10-K15: the header the `skills` section opens with. */
const val SKILLS_HEADER = "SKILLS (this applies to your whole session, including after a context compaction)."

/**
 * P10-K15: the `skills` section of a first turn (K-D10). Pure.
 *
 * - [attached] (the session's frozen [RoleInstance.skillIds], in effective order) are injected in FULL as "always
 *   available", while their bodies fit [SkillBudgets.bodyChars]. A skill that does not fit drops to one line -- its
 *   name, its description and the `show` command; a body is never cut in the middle. Later, smaller ones still fit.
 * - An attached id no longer in [library] renders as `<id> (deleted)` (deleting an attached skill is allowed, K-D9).
 * - Every OTHER skill of [library] that is not `attachedOnly` gets one `name: description` line, by name. An
 *   `attachedOnly` skill appears only when attached.
 * - Every one-line reference shares the index cap: at most [SkillBudgets.indexLines] lines and
 *   [SkillBudgets.indexChars] chars, never a line cut, then `N more: infantry skill list`.
 * - Nothing attached and nothing to index gives null, so the first turn is exactly the pre-K15 text.
 */
fun renderSkills(attached: List<String>, library: List<SkillView>, budgets: SkillBudgets = SkillBudgets()): String? {
    val byId = library.associateBy { it.id }
    val attachedIds = attached.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    val attachedSet = attachedIds.toSet()

    val inlined = mutableListOf<String>()
    val references = mutableListOf<String>()
    var bodyUsed = 0
    for (id in attachedIds) {
        val skill = byId[id]
        if (skill == null) {
            references += "- $id (deleted)"
        } else {
            val block = inline(skill)
            // +1: the newline that joins it to the next part; the ceiling holds on the rendered text.
            if (bodyUsed + block.length + 1 <= budgets.bodyChars) {
                inlined += block
                bodyUsed += block.length + 1
                continue
            }
            references += "- ${line(skill)} (attached to you, too long to inline: run `infantry skill show ${skill.name}`)"
        }
    }
    val attachedReferences = references.size
    library.asSequence()
        .filter { it.id !in attachedSet && !it.attachedOnly }
        .sortedBy { it.name }
        .forEach { references += "- ${line(it)}" }
    if (inlined.isEmpty() && references.isEmpty()) return null

    val shown = mutableListOf<String>()
    var indexUsed = 0
    for (ref in references) {
        if (shown.size >= budgets.indexLines || indexUsed + ref.length + 1 > budgets.indexChars) break
        shown += ref
        indexUsed += ref.length + 1
    }
    val hidden = references.size - shown.size
    val shownAttached = shown.take(attachedReferences)
    val shownIndex = shown.drop(attachedReferences)

    return buildList {
        add(SKILLS_HEADER)
        if (inlined.isNotEmpty() || shownAttached.isNotEmpty()) {
            add("Attached to you (always available; follow a skill whenever its description applies to your work):")
            addAll(inlined)
            addAll(shownAttached)
        }
        if (shownIndex.isNotEmpty()) {
            add(
                "Other skills in the Infantry AI daemon, one `name: description` line each. When a description matches your " +
                    "work, run `infantry skill show <name>` and follow it:",
            )
            addAll(shownIndex)
        }
        if (hidden > 0) add("- $hidden more: `infantry skill list`")
    }.joinToString("\n")
}

/** One attached skill in full: its heading, its description, then its body, untouched. All of it counts to the budget. */
private fun inline(skill: SkillView): String =
    "### Skill: ${skill.name}" + skill.description.trim().takeIf { it.isNotEmpty() }?.let { "\n$it" }.orEmpty() + "\n" + skill.body

private fun line(skill: SkillView): String {
    val description = skill.description.replace('\r', ' ').replace('\n', ' ').trim()
    return if (description.isEmpty()) skill.name else "${skill.name}: $description"
}

/**
 * P10-K15: where a first turn's `skills` section comes from -- the daemon's skill [library] and the project's
 * `includeSkillsContext` switch, both read when the turn renders (bodies are read at render, ids are frozen, K-D11).
 */
class SkillsSource(
    private val library: () -> List<SkillView>,
    private val includeSkillsContext: () -> Boolean? = { null },
    private val budgets: SkillBudgets = SkillBudgets(),
) : com.superiorsoftllc.infantry.context.SkillsSection {
    /**
     * The section for a session frozen as [instance]. No instance (an anonymous spawn) or a false switch ([include], the
     * ticket's resolved value when the caller has one, else the project's) gives null: no section at all.
     */
    fun section(instance: RoleInstance?, include: Boolean? = null): String? = section(instance?.skillIds, include)

    /** The same, from the frozen ids alone; null [skillIds] = no instance. */
    override fun section(skillIds: List<String>?, include: Boolean?): String? {
        if (skillIds == null) return null
        if (!(include ?: includeSkillsContext() ?: true)) return null
        return renderSkills(skillIds, library(), budgets)
    }

    companion object {
        /** No library wired: every first turn is exactly the pre-K15 text. */
        val NONE = SkillsSource(library = { emptyList() })
    }
}
