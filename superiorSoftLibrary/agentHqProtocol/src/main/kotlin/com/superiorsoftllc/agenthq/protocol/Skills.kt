package com.superiorsoftllc.agenthq.protocol

import kotlinx.serialization.Serializable

/**
 * Contract 1.45.0 (P10-B): the payload of `skill_save`. Saving a [name] that already exists UPDATES that skill
 * (same id, new description / body / tags, `updatedAt` moves); otherwise it creates one. Limits are enforced by the
 * daemon: name <= 120 chars, description <= 500 chars, body <= 64 KB, <= 16 tags of <= 32 chars each. Decoded
 * strictly, like `prompt_save`.
 */
@Serializable
data class SkillSave(
    val name: String,
    val description: String = "",
    val body: String,
    val tags: List<String> = emptyList(),
    /**
     * Contract 1.56.0 (P10-K12): true hides the skill from every session it is not attached to (the first-turn index and
     * `agenthq skill list`). Absent or null KEEPS the stored flag on an update, so a pre-1.56.0 client re-saving a skill
     * cannot un-hide it; a new skill gets false.
     */
    val attachedOnly: Boolean? = null,
) {
    companion object {
        fun decode(text: String): SkillSave = StrictJson.decode(serializer(), text, "skill_save payload")
    }
}

/**
 * Contract 1.45.0 (P10-B): one saved skill, as the `skill_item` and `skills` frames carry it. `skill_list` returns every
 * skill, `attachedOnly` ones included (a UI shows the toggle); hiding them is the reader's job (`agenthq skill list`, K15).
 */
@Serializable
data class SkillView(
    val id: String,
    val name: String,
    val description: String = "",
    val body: String,
    val tags: List<String> = emptyList(),
    val createdAt: Long,
    val updatedAt: Long,
    /** Contract 1.56.0 (P10-K12): the stored flag, see [SkillSave.attachedOnly]. */
    val attachedOnly: Boolean = false,
)
