package com.superiorsoftllc.agenthq.persistence

import com.superiorsoftllc.agenthq.protocol.SkillSave
import com.superiorsoftllc.agenthq.protocol.SkillView
import java.util.UUID

/**
 * P10-B: the skills library. Owns every rule (limits, tag normalisation, name uniqueness, save-as-update);
 * the [SkillRepository] only stores rows. A refusal is an [IllegalArgumentException] naming the problem and
 * nothing is written.
 */
class SkillStore(
    private val repository: SkillRepository,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    /** Creates the skill, or updates the one that has this name (keeping its id and createdAt). */
    fun save(save: SkillSave): SkillView {
        val name = save.name.trim()
        require(name.isNotEmpty()) { "name must not be blank" }
        require(name.length <= MAX_NAME_CHARS) { "name is ${name.length} chars; the limit is $MAX_NAME_CHARS" }
        val description = save.description.trim()
        require(description.length <= MAX_DESCRIPTION_CHARS) { "description is ${description.length} chars; the limit is $MAX_DESCRIPTION_CHARS" }
        require(save.body.isNotBlank()) { "body must not be blank" }
        val bodyBytes = save.body.toByteArray(Charsets.UTF_8).size
        require(bodyBytes <= MAX_BODY_BYTES) { "body is $bodyBytes bytes; the limit is $MAX_BODY_BYTES (64 KB)" }
        val tags = save.tags.map(::normalizeTag).filter { it.isNotEmpty() }.distinct()
        tags.firstOrNull { it.length > MAX_TAG_CHARS }?.let { throw IllegalArgumentException("tag '${it.take(40)}' is ${it.length} chars; the limit is $MAX_TAG_CHARS") }
        require(tags.size <= MAX_TAGS) { "${tags.size} tags; the limit is $MAX_TAGS tags" }
        val at = clock()
        return repository.upsertByName(name, description, save.body, tags, at, save.attachedOnly, newId)
    }

    /** Every skill by name; with [tag] only those carrying it. */
    fun list(tag: String?): List<SkillView> {
        val wanted = normalizeTag(tag.orEmpty())
        val all = repository.all()
        return if (wanted.isEmpty()) all else all.filter { wanted in it.tags }
    }

    fun get(id: String): SkillView? = repository.get(id)

    /**
     * P10-K12: `skill_get`'s lookup -- the skill with id [idOrName], else the one with that exact name (trimmed). Ids are
     * tried first, so a skill whose name happens to equal another skill's id is still reachable by its own id.
     */
    fun find(idOrName: String): SkillView? {
        val key = idOrName.trim()
        if (key.isEmpty()) return null
        return repository.get(key) ?: repository.getByName(key)
    }

    /** Deletes the skill; false when there was none with that id. */
    fun delete(id: String): Boolean = repository.delete(id)

    private fun normalizeTag(tag: String) = tag.trim().lowercase()

    companion object {
        const val MAX_NAME_CHARS = 120
        const val MAX_DESCRIPTION_CHARS = 500
        const val MAX_BODY_BYTES = 64 * 1024
        const val MAX_TAG_CHARS = 32
        const val MAX_TAGS = 16
    }
}
