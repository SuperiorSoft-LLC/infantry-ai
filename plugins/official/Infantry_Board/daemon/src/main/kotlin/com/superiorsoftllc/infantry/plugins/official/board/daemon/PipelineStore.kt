package com.superiorsoftllc.infantry.plugins.official.board.daemon

import com.superiorsoftllc.infantry.daemon.api.storage.PluginStorage
import com.superiorsoftllc.infantry.pipeline.PipelineCheck
import com.superiorsoftllc.infantry.pipeline.PipelineConfig
import com.superiorsoftllc.infantry.pipeline.PipelineEntry
import com.superiorsoftllc.infantry.pipeline.PipelineRefusal
import com.superiorsoftllc.infantry.pipeline.PipelineState
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * PIPE-1 / DP-6: a project's optional pipeline over the plugin storage, adopting the daemon's `pipeline_config`,
 * `pipeline_entries` and `pipeline_checks` tables (module.json `daemon.storage.adopt`; core creates them). No config row is
 * the default and means the project has no pipeline: [state] reports `configured = false` and every write is refused `no_pipeline`.
 *
 * Rules: stage names are trimmed, non-blank, unique; a stage change must name a configured stage (any stage to any
 * stage, so a failed commit can be retested); with [PipelineConfig.exclusiveCheck] a second `start` in a scope that
 * already has a running check is refused `check_running`, naming the holder. Entries are append-only; the current
 * stage of a commit is its newest entry.
 */
class PipelineStore(
    private val db: PluginStorage,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val names = ListSerializer(String.serializer())

    @Synchronized
    fun configure(projectId: String, stages: List<String>?, exclusiveCheck: Boolean, clear: Boolean): PipelineState {
        val project = projectId.trim().also { require(it.isNotEmpty()) { "projectId is required" } }
        if (clear) {
            db.execute("DELETE FROM pipeline_config WHERE project_id = ?", listOf(project))
            return state(project)
        }
        val list = (stages ?: emptyList()).map { it.trim() }
        require(list.isNotEmpty()) { "stages is required: the ordered stage names, or clear to remove the pipeline" }
        require(list.none { it.isEmpty() || ',' in it || '\n' in it }) { "a stage name is non-blank, one line, without commas" }
        require(list.distinct().size == list.size) { "stage names must be unique" }
        db.execute(
            "INSERT OR REPLACE INTO pipeline_config(project_id, stages, exclusive_check) VALUES (?, ?, ?)",
            listOf(project, Json.encodeToString(names, list), if (exclusiveCheck) 1L else 0L),
        )
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
        db.execute(
            "INSERT INTO pipeline_entries(project_id, commit_sha, stage, moved_by, evidence, moved_at) VALUES (?, ?, ?, ?, ?, ?)",
            listOf(project, sha, name, by, evidence?.takeIf { it.isNotBlank() }, clock()),
        )
        return state(project)
    }

    @Synchronized
    fun check(projectId: String, action: String, scope: String, commit: String?, what: String?, by: String?): PipelineState {
        val project = projectId.trim()
        val config = requireConfig(project)
        val area = scope.trim()
        val key = listOf(project, area)
        val held = db.query("SELECT * FROM pipeline_checks WHERE project_id = ? AND scope = ?", key).firstOrNull()
        when (action.trim()) {
            "start" -> {
                val sha = commit?.trim()?.takeIf { it.isNotEmpty() } ?: throw IllegalArgumentException("start needs a commit")
                val job = what?.trim()?.takeIf { it.isNotEmpty() } ?: throw IllegalArgumentException("start needs what it runs")
                if (held != null) {
                    if (config.exclusiveCheck) {
                        throw PipelineRefusal(
                            PipelineRefusal.CHECK_RUNNING,
                            "'${held["what"]}' on ${held["commit_sha"]} is already running in ${scopeLabel(area)} " +
                                "(started ${held["started_at"]}, last output ${held["last_output_at"]}); finish it first",
                        )
                    }
                    db.execute("DELETE FROM pipeline_checks WHERE project_id = ? AND scope = ?", key)
                }
                val now = clock()
                db.execute(
                    "INSERT INTO pipeline_checks(project_id, scope, commit_sha, what, started_by, started_at, last_output_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    listOf(project, area, sha, job, by, now, now),
                )
            }
            "ping" -> {
                held ?: throw PipelineRefusal(PipelineRefusal.NO_CHECK, "no running check in ${scopeLabel(area)}")
                db.execute("UPDATE pipeline_checks SET last_output_at = ? WHERE project_id = ? AND scope = ?", listOf(clock()) + key)
            }
            "finish" -> {
                held ?: throw PipelineRefusal(PipelineRefusal.NO_CHECK, "no running check in ${scopeLabel(area)}")
                db.execute("DELETE FROM pipeline_checks WHERE project_id = ? AND scope = ?", key)
            }
            else -> throw IllegalArgumentException("unknown check action '$action'; use start, ping or finish")
        }
        return state(project)
    }

    fun state(projectId: String): PipelineState {
        val project = projectId.trim()
        val config = config(project) ?: return PipelineState(project, configured = false)
        val history = db.query("SELECT * FROM pipeline_entries WHERE project_id = ? ORDER BY id DESC", listOf(project)).map {
            PipelineEntry(it.str("commit_sha"), it.str("stage"), it["moved_by"] as? String, it.long("moved_at"), it["evidence"] as? String)
        }
        return PipelineState(
            projectId = project,
            configured = true,
            config = config,
            commits = history.distinctBy { it.commit },
            history = history,
            checks = db.query("SELECT * FROM pipeline_checks WHERE project_id = ? ORDER BY started_at", listOf(project)).map {
                PipelineCheck(it.str("scope"), it.str("commit_sha"), it.str("what"), it["started_by"] as? String, it.long("started_at"), it.long("last_output_at"))
            },
        )
    }

    private fun Map<String, Any?>.str(key: String) = this[key] as String
    private fun Map<String, Any?>.long(key: String) = (this[key] as Number).toLong()

    private fun config(project: String): PipelineConfig? =
        db.query("SELECT * FROM pipeline_config WHERE project_id = ?", listOf(project)).firstOrNull()?.let {
            PipelineConfig(Json.decodeFromString(names, it.str("stages")), it.long("exclusive_check") != 0L)
        }

    private fun requireConfig(project: String): PipelineConfig = config(project)
        ?: throw PipelineRefusal(PipelineRefusal.NO_PIPELINE, "project '$project' has no pipeline configured (infantry pipeline configure <stage>...)")

    private fun scopeLabel(scope: String) = if (scope.isEmpty()) "the default scope" else "scope '$scope'"
}
