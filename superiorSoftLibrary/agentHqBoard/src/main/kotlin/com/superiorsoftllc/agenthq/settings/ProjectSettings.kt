package com.superiorsoftllc.agenthq.settings

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

interface ProjectSettings {
    fun all(): JsonObject
    fun set(key: String, value: JsonElement): JsonObject

    /** Null means that no project-level value has ever been stored. */
    fun includeLinkedTasksContext(): Boolean?

    /** P10-K11: the project's skills-section switch; null means that no project-level value has ever been stored. */
    fun includeSkillsContext(): Boolean? = null

    /** P10-K11: the project baseline skill ids, in attach order; empty when none was ever stored. */
    fun skillIds(): List<String> = emptyList()
}
