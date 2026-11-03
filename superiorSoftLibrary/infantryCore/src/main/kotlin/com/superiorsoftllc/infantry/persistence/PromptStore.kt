// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.persistence

import com.superiorsoftllc.infantry.protocol.PromptSave
import com.superiorsoftllc.infantry.protocol.PromptView
import java.util.UUID

/**
 * P10-A: the prompt library. Owns every rule (limits, tag normalisation, name uniqueness, save-as-update);
 * the [PromptRepository] only stores rows. A refusal is an [IllegalArgumentException] naming the problem and
 * nothing is written.
 */
class PromptStore(
    private val repository: PromptRepository,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    /** Creates the prompt, or updates the one that has this name (keeping its id and createdAt). */
    fun save(save: PromptSave): PromptView {
        val name = save.name.trim()
        require(name.isNotEmpty()) { "name must not be blank" }
        require(name.length <= MAX_NAME_CHARS) { "name is ${name.length} chars; the limit is $MAX_NAME_CHARS" }
        require(save.body.isNotBlank()) { "body must not be blank" }
        val bodyBytes = save.body.toByteArray(Charsets.UTF_8).size
        require(bodyBytes <= MAX_BODY_BYTES) { "body is $bodyBytes bytes; the limit is $MAX_BODY_BYTES (64 KB)" }
        val tags = save.tags.map(::normalizeTag).filter { it.isNotEmpty() }.distinct()
        tags.firstOrNull { it.length > MAX_TAG_CHARS }?.let { throw IllegalArgumentException("tag '${it.take(40)}' is ${it.length} chars; the limit is $MAX_TAG_CHARS") }
        require(tags.size <= MAX_TAGS) { "${tags.size} tags; the limit is $MAX_TAGS tags" }
        val at = clock()
        return repository.upsertByName(name, save.body, tags, at, newId)
    }

    /** Every prompt by name; with [tag] only those carrying it. */
    fun list(tag: String?): List<PromptView> {
        val wanted = normalizeTag(tag.orEmpty())
        val all = repository.all()
        return if (wanted.isEmpty()) all else all.filter { wanted in it.tags }
    }

    fun get(id: String): PromptView? = repository.get(id)

    /** Deletes the prompt; false when there was none with that id. */
    fun delete(id: String): Boolean = repository.delete(id)

    private fun normalizeTag(tag: String) = tag.trim().lowercase()

    companion object {
        const val MAX_NAME_CHARS = 120
        const val MAX_BODY_BYTES = 64 * 1024
        const val MAX_TAG_CHARS = 32
        const val MAX_TAGS = 16
    }
}
