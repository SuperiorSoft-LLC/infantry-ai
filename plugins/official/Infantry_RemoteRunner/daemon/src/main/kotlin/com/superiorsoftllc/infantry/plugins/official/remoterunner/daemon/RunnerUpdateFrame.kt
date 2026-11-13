// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.remoterunner.daemon

import com.superiorsoftllc.infantry.daemon.api.Caller
import com.superiorsoftllc.infantry.daemon.api.DaemonApi
import com.superiorsoftllc.infantry.daemon.api.Frame
import com.superiorsoftllc.infantry.plugin.api.ProgressSpec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.util.concurrent.ConcurrentHashMap
import com.superiorsoftllc.infantry.daemon.api.runners.RunnerLinkWire as F

/**
 * RUNNER-UPDATE-1: the `runner_update` frame (`{"name": <runner>}`, owner only) the Runners card's "Update runner" sends. The update
 * takes minutes (a pull, a recreate, the wait for the runner to answer), longer than one plugin call may block, so the frame answers
 * at once (`{"ok": true, "started": true}`) and [RunnerUpdater] runs on its own thread. Each step and the outcome are pushed as
 * [STATUS] frames (`{"name", "state": running|updated|failed|manual, "step", "detail", "command"?, "image"?, "contract"?}`) and shown
 * as a progress task with the same steps. One update per runner at a time.
 */
class RunnerUpdateFrame(private val api: DaemonApi, private val updater: RunnerUpdater) {
    private val running = ConcurrentHashMap.newKeySet<String>()

    fun handle(frame: Frame, caller: Caller): Frame {
        if (!caller.owner) return answer(false, "only the owner may update a runner")
        val name = runCatching { (F.json.parseToJsonElement(frame.data).jsonObject["name"] as? JsonPrimitive)?.contentOrNull }.getOrNull()?.trim()
        if (name.isNullOrEmpty()) return answer(false, "name is required")
        if (name !in api.runnerLinks.paired()) return answer(false, "no paired runner '${name.filter { it in ' '..'~' }.take(SHOWN_NAME)}'")
        if (!running.add(name)) return answer(false, "runner '$name' is already being updated")
        val thread = Thread({ run(name) }, "runner-update-$name")
        thread.isDaemon = true
        thread.start()
        return Frame(TYPE, JsonObject(mapOf("ok" to JsonPrimitive(true), "started" to JsonPrimitive(true), "name" to JsonPrimitive(name))).toString())
    }

    private fun run(name: String) {
        val progress = api.progress.start(ProgressSpec(title = "Update runner $name", steps = RunnerUpdater.STEPS))
        try {
            val outcome = updater.update(name) { step, detail ->
                progress.step(step, RunnerUpdater.STEPS.indexOf(step))
                status(name, "running", step, detail)
            }
            when (outcome) {
                is RunnerUpdater.Outcome.Updated -> {
                    progress.done("runner '$name' answers on ${outcome.image}")
                    api.log("[runner-update] $name: ${outcome.previousImage} -> ${outcome.image} (contract ${outcome.contract})")
                    status(name, "updated", "", "runner '$name' answers on ${outcome.image} (contract ${outcome.contract}); same name, volumes, ports and env", image = outcome.image, contract = outcome.contract)
                }
                is RunnerUpdater.Outcome.Failed -> {
                    progress.fail(outcome.reason)
                    api.log("[runner-update] $name failed: ${outcome.reason}")
                    status(name, "failed", "", outcome.reason)
                }
                is RunnerUpdater.Outcome.Manual -> {
                    progress.done("run the update command on the runner's machine")
                    status(name, "manual", "", outcome.reason, command = outcome.command)
                }
            }
        } catch (e: Exception) {
            progress.fail("update stopped: ${e.message}")
            status(name, "failed", "", "update stopped: ${e.message ?: e::class.simpleName}")
        } finally {
            running.remove(name)
        }
    }

    private fun status(name: String, state: String, step: String, detail: String, command: String? = null, image: String? = null, contract: String? = null) {
        val fields = listOfNotNull(
            "name" to name, "state" to state, "step" to step, "detail" to detail,
            command?.let { "command" to it }, image?.let { "image" to it }, contract?.let { "contract" to it },
        ).associate { it.first to JsonPrimitive(it.second) }
        runCatching { api.events.broadcast(STATUS, JsonObject(fields).toString()) }
    }

    private fun answer(ok: Boolean, error: String): Frame =
        Frame(TYPE, JsonObject(mapOf("ok" to JsonPrimitive(ok), "error" to JsonPrimitive(error))).toString())

    companion object {
        const val TYPE = "runner_update"
        const val STATUS = "runner_update_status"
        private const val SHOWN_NAME = 64
    }
}
