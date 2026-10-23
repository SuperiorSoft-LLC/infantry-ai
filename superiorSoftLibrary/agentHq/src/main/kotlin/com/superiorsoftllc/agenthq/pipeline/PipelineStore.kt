package com.superiorsoftllc.agenthq.pipeline

import com.superiorsoftllc.agenthq.persistence.SqlAgentHqStore
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * PIPE-1: a project's optional pipeline over the daemon's SQLite file. No config row is the default and means the
 * project has no pipeline: [state] reports `configured = false` and every write is refused `no_pipeline`.
 *
 * Rules: stage names are trimmed, non-blank, unique; a stage change must name a configured stage (any stage to any
 * stage, so a failed commit can be retested); with [PipelineConfig.exclusiveCheck] a second `start` in a scope that
 * already has a running check is refused `check_running`, naming the holder. Entries are append-only; the current
 * stage of a commit is its newest entry.
 */
class PipelineStore(
    private val store: SqlAgentHqStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val q get() = store.database.pipelineQueries
    private val names = ListSerializer(String.serializer())

    @Synchronized
    fun configure(projectId: String, stages: List<String>?, exclusiveCheck: Boolean, clear: Boolean): PipelineState {
        val project = projectId.trim().also { require(it.isNotEmpty()) { "projectId is required" } }
        if (clear) {
            q.deletePipelineConfig(project)
            return state(project)
        }
        val list = (stages ?: emptyList()).map { it.trim() }
        require(list.isNotEmpty()) { "stages is required: the ordered stage names, or clear to remove the pipeline" }
        require(list.none { it.isEmpty() || ',' in it || '\n' in it }) { "a stage name is non-blank, one line, without commas" }
        require(list.distinct().size == list.size) { "stage names must be unique" }
        q.upsertPipelineConfig(project, Json.encodeToString(names, list), if (exclusiveCheck) 1 else 0)
        return state(project)
    }

    @Synchronized
    fun record(projectId: String, commit: String, stage: String, evidence: String?, by: String?): PipelineState {
        val project = projectId.trim()
        val config = requireConfig(project)
        val sha = commit.trim().also { require(it.isNotEmpty()) { "commit is required" } }
        val name = stage.trim()
        if (name !in config.stages) {
            throw PipelineRefusal(PipelineRefusal.UNKNOWN_STAGE, "'$name' is not a stage of this pipeline; stages: ${config.stages.joinToString(", ")}")
        }
        q.insertPipelineEntry(project, sha, name, by, evidence?.takeIf { it.isNotBlank() }, clock())
        return state(project)
    }

    @Synchronized
    fun check(projectId: String, action: String, scope: String, commit: String?, what: String?, by: String?): PipelineState {
        val project = projectId.trim()
        val config = requireConfig(project)
        val area = scope.trim()
        val held = q.getPipelineCheck(project, area).executeAsOneOrNull()
        when (action.trim()) {
            "start" -> {
                val sha = commit?.trim()?.takeIf { it.isNotEmpty() } ?: throw IllegalArgumentException("start needs a commit")
                val job = what?.trim()?.takeIf { it.isNotEmpty() } ?: throw IllegalArgumentException("start needs what it runs")
                if (held != null) {
                    if (config.exclusiveCheck) {
                        throw PipelineRefusal(
                            PipelineRefusal.CHECK_RUNNING,
                            "'${held.what}' on ${held.commit_sha} is already running in ${scopeLabel(area)} " +
                                "(started ${held.started_at}, last output ${held.last_output_at}); finish it first",
                        )
                    }
                    q.deletePipelineCheck(project, area)
                }
                val now = clock()
                q.insertPipelineCheck(project, area, sha, job, by, now, now)
            }
            "ping" -> {
                held ?: throw PipelineRefusal(PipelineRefusal.NO_CHECK, "no running check in ${scopeLabel(area)}")
                q.touchPipelineCheck(clock(), project, area)
            }
            "finish" -> {
                held ?: throw PipelineRefusal(PipelineRefusal.NO_CHECK, "no running check in ${scopeLabel(area)}")
                q.deletePipelineCheck(project, area)
            }
            else -> throw IllegalArgumentException("unknown check action '$action'; use start, ping or finish")
        }
        return state(project)
    }

    fun state(projectId: String): PipelineState {
        val project = projectId.trim()
        val config = config(project) ?: return PipelineState(project, configured = false)
        val history = q.getPipelineEntries(project).executeAsList().map {
            PipelineEntry(it.commit_sha, it.stage, it.moved_by, it.moved_at, it.evidence)
        }
        return PipelineState(
            projectId = project,
            configured = true,
            config = config,
            commits = history.distinctBy { it.commit },
            history = history,
            checks = q.getPipelineChecks(project).executeAsList().map {
                PipelineCheck(it.scope, it.commit_sha, it.what, it.started_by, it.started_at, it.last_output_at)
            },
        )
    }

    private fun config(project: String): PipelineConfig? = q.getPipelineConfig(project).executeAsOneOrNull()?.let {
        PipelineConfig(Json.decodeFromString(names, it.stages), it.exclusive_check != 0L)
    }

    private fun requireConfig(project: String): PipelineConfig = config(project)
        ?: throw PipelineRefusal(PipelineRefusal.NO_PIPELINE, "project '$project' has no pipeline configured (agenthq pipeline configure <stage>...)")

    private fun scopeLabel(scope: String) = if (scope.isEmpty()) "the default scope" else "scope '$scope'"
}
