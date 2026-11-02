package com.superiorsoftllc.agenthq.protocol

import kotlinx.serialization.Serializable

/**
 * SKILLS-GLOBAL-1b: layered skills (global / project / role). One request shape for save, list and delete; `scope` is
 * `global`, `project` or `role`. `projectId` is required for project and role scopes (the daemon fills it from the
 * caller's project when absent), `role` for the role scope. Answers: `layer_skills` (a list of [LayerSkillView]); a
 * refusal is an `error` `layer_skill_<op> refused: <reason>`.
 */
@Serializable
data class LayerSkillRequest(
    val scope: String,
    val projectId: String? = null,
    val role: String? = null,
    val name: String? = null,
    val description: String = "",
    val body: String? = null,
)

@Serializable
data class LayerSkillView(
    val scope: String,
    val name: String,
    val description: String = "",
    val body: String = "",
    val projectId: String? = null,
    val role: String? = null,
)

/** The `layer_skills` answer: one scope's skills (the scope is named so an empty list still says which list it is). */
@Serializable
data class LayerSkillList(
    val scope: String,
    val projectId: String? = null,
    val role: String? = null,
    val skills: List<LayerSkillView> = emptyList(),
)

object LayerSkillFrames {
    /** Setting key of the inline-length cutoff (chars); absent = 1500. */
    const val INLINE_MAX_SETTING = "limits.skills.inlineMaxChars"
}
