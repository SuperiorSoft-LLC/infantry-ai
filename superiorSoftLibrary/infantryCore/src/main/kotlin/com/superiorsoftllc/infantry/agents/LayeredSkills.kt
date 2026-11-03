// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.agents

import com.superiorsoftllc.infantry.persistence.InfantryStore
import com.superiorsoftllc.infantry.persistence.SettingRow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** SKILLS-GLOBAL-1: the three skill layers, widest first. A narrower layer overrides a wider one with the same name. */
enum class SkillLayer(val label: String) { GLOBAL("global"), PROJECT("project"), ROLE("role") }

/** One layered skill: a name, a one-line description and a body. */
@Serializable
data class LayerSkill(val name: String, val description: String = "", val body: String)

/** A skill as an agent sees it: which layer it won from. */
data class ResolvedSkill(val skill: LayerSkill, val layer: SkillLayer)

/** Where a layer's skills live: [scopeKey] picks the layer's settings row. */
data class SkillScope(val layer: SkillLayer, val projectId: String? = null, val role: String? = null) {
    init {
        when (layer) {
            SkillLayer.GLOBAL -> {}
            SkillLayer.PROJECT -> require(!projectId.isNullOrBlank()) { "project scope needs a project id" }
            SkillLayer.ROLE -> require(!projectId.isNullOrBlank() && !role.isNullOrBlank()) { "role scope needs a project id and a role" }
        }
    }

    val key: String
        get() = when (layer) {
            SkillLayer.GLOBAL -> "skills.layer.global"
            SkillLayer.PROJECT -> "skills.layer.project.$projectId"
            SkillLayer.ROLE -> "skills.layer.role.$projectId.$role"
        }
}

/** Persists layered skills as one JSON array per scope in the settings table (no schema change). CRUD is by name. */
class LayeredSkillStore(private val store: InfantryStore, private val clock: () -> Long = System::currentTimeMillis) {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(LayerSkill.serializer())

    fun list(scope: SkillScope): List<LayerSkill> =
        store.settings.get(scope.key)?.value?.let { json.decodeFromString(serializer, it) }.orEmpty().sortedBy { it.name }

    fun get(scope: SkillScope, name: String): LayerSkill? = list(scope).firstOrNull { it.name == name.trim() }

    /** Creates or replaces (by name) a skill in [scope]. */
    fun save(scope: SkillScope, skill: LayerSkill): LayerSkill {
        val name = skill.name.trim()
        require(name.isNotEmpty() && name.length <= SkillStore_MAX_NAME) { "name must be 1..$SkillStore_MAX_NAME chars" }
        require(skill.body.isNotBlank()) { "body must not be blank" }
        require(skill.body.toByteArray().size <= 64 * 1024) { "body exceeds 64 KB" }
        val saved = skill.copy(name = name, description = skill.description.trim())
        write(scope, list(scope).filterNot { it.name == name } + saved)
        return saved
    }

    /** True when a skill of that name existed in [scope]. */
    fun delete(scope: SkillScope, name: String): Boolean {
        val before = list(scope)
        val after = before.filterNot { it.name == name.trim() }
        if (after.size == before.size) return false
        write(scope, after)
        return true
    }

    /** The skills one agent gets: global, then its project, then its role; narrower wins on a name clash. */
    fun resolve(projectId: String?, role: String?): List<ResolvedSkill> {
        val byName = linkedMapOf<String, ResolvedSkill>()
        fun add(scope: SkillScope) = list(scope).forEach { byName[it.name] = ResolvedSkill(it, scope.layer) }
        add(SkillScope(SkillLayer.GLOBAL))
        if (!projectId.isNullOrBlank()) {
            add(SkillScope(SkillLayer.PROJECT, projectId))
            if (!role.isNullOrBlank()) add(SkillScope(SkillLayer.ROLE, projectId, role))
        }
        return byName.values.sortedWith(compareBy({ it.layer.ordinal }, { it.skill.name }))
    }

    private fun write(scope: SkillScope, skills: List<LayerSkill>) =
        store.settings.put(SettingRow(scope.key, json.encodeToString(serializer, skills), clock()))

    private companion object {
        const val SkillStore_MAX_NAME = 120
    }
}

/**
 * The layered `skills` brief section, or null with nothing to show. Each skill says its layer; a body at most
 * [inlineMaxChars] long is inlined, a longer one is a description line plus `infantry skill show`.
 */
fun renderLayeredSkills(skills: List<ResolvedSkill>, inlineMaxChars: Int = 1_500): String? {
    if (skills.isEmpty()) return null
    return buildList {
        add("LAYERED SKILLS (global -> project -> role; a narrower layer overrides a wider one of the same name; follow them for your whole session).")
        for (r in skills) {
            val head = "### Skill: ${r.skill.name} [${r.layer.label}]"
            val desc = r.skill.description.replace('\n', ' ').trim()
            if (r.skill.body.length <= inlineMaxChars) {
                add(head + (if (desc.isNotEmpty()) "\n$desc" else "") + "\n" + r.skill.body)
            } else {
                add("- ${r.skill.name} [${r.layer.label}]: ${desc.ifEmpty { "(no description)" }} (long: run `infantry skill show ${r.skill.name} --scope ${r.layer.label}`)")
            }
        }
    }.joinToString("\n")
}
