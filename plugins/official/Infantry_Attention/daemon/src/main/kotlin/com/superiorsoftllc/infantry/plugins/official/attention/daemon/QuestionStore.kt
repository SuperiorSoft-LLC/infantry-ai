// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.attention.daemon

import com.superiorsoftllc.infantry.daemon.api.storage.PluginStorage

/** ATTN-2c: the durable owner questions, rows of the adopted `owner_questions` table (module.json `daemon.storage.adopt`; core creates it). Rows are never deleted. */
interface OwnerQuestionRepository {
    fun insert(question: OwnerQuestion)
    /** Writes the resolution columns of [question]. */
    fun update(question: OwnerQuestion)
    fun get(id: String): OwnerQuestion?
    /** Oldest first. */
    fun all(): List<OwnerQuestion>
}

class InMemoryOwnerQuestionRepository : OwnerQuestionRepository {
    private val rows = LinkedHashMap<String, OwnerQuestion>()

    @Synchronized override fun insert(question: OwnerQuestion) {
        require(question.id !in rows) { "question '${question.id}' exists" }
        rows[question.id] = question
    }

    @Synchronized override fun update(question: OwnerQuestion) {
        if (question.id in rows) rows[question.id] = question
    }

    @Synchronized override fun get(id: String) = rows[id]

    @Synchronized override fun all() = rows.values.sortedWith(compareBy({ it.createdAt }, { it.id }))
}

class StorageOwnerQuestionRepository(private val db: PluginStorage) : OwnerQuestionRepository {
    override fun insert(question: OwnerQuestion) {
        db.execute(
            "INSERT INTO owner_questions(id, project_id, session_id, agent_id, role, question, options, recommended, reason, " +
                "allow_other, default_after_min, state, answer, other_text, resolved_by, created_at, resolved_at, overridden) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            listOf(
                question.id, question.projectId, question.sessionId, question.agentId, question.role, question.question,
                QuestionJson.options(question.options).toString(), question.recommended, question.reason,
                if (question.allowOther) 1L else 0L, question.defaultAfterMin?.toLong(), question.state, question.answer,
                question.otherText, question.resolvedBy, question.createdAt, question.resolvedAt, if (question.overridden) 1L else 0L,
            ),
        )
    }

    override fun update(question: OwnerQuestion) {
        db.execute(
            "UPDATE owner_questions SET state = ?, answer = ?, other_text = ?, resolved_by = ?, resolved_at = ?, overridden = ? WHERE id = ?",
            listOf(question.state, question.answer, question.otherText, question.resolvedBy, question.resolvedAt, if (question.overridden) 1L else 0L, question.id),
        )
    }

    override fun get(id: String) = db.query("SELECT * FROM owner_questions WHERE id = ?", listOf(id)).firstOrNull()?.let(::row)

    override fun all() = db.query("SELECT * FROM owner_questions ORDER BY created_at ASC, id ASC").map(::row)

    private fun row(r: Map<String, Any?>) = OwnerQuestion(
        id = r["id"] as String,
        projectId = r["project_id"] as String?,
        sessionId = r["session_id"] as String,
        agentId = r["agent_id"] as String?,
        role = r["role"] as String?,
        question = r["question"] as String,
        options = QuestionJson.parseOptions(r["options"] as String),
        recommended = r["recommended"] as String,
        reason = r["reason"] as String,
        allowOther = true,
        defaultAfterMin = (r["default_after_min"] as Number?)?.toInt(),
        state = r["state"] as String,
        answer = r["answer"] as String?,
        otherText = r["other_text"] as String?,
        resolvedBy = r["resolved_by"] as String?,
        createdAt = (r["created_at"] as Number).toLong(),
        resolvedAt = (r["resolved_at"] as Number?)?.toLong(),
        overridden = ((r["overridden"] as Number?)?.toLong() ?: 0L) != 0L,
    )
}
