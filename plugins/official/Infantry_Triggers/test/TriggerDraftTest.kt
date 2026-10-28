package com.superiorsoftllc.agenthq.plugins.official.triggers

import com.superiorsoftllc.agenthq.ui.*

import com.superiorsoftllc.agenthq.ui.protocol.TriggerAction
import com.superiorsoftllc.agenthq.ui.protocol.TriggerView
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** TRIGGER-NUDGE (contract 1.105.0): the trigger form's model -- interval parsing, validation, the payload, edits. */
class TriggerDraftTest {

    private fun JsonObject.text(key: String) = get(key)?.jsonPrimitive?.content

    @Test
    fun intervalIsWholeMinutesAtLeastOne() {
        assertEquals(30 * 60_000L, parseIntervalMinutes("30"))
        assertEquals(60_000L, parseIntervalMinutes("1"))
        assertEquals(15 * 60_000L, parseIntervalMinutes(" 15 "))
        assertNull(parseIntervalMinutes("0"))
        assertNull(parseIntervalMinutes("-5"))
        assertNull(parseIntervalMinutes("0.5"), "the daemon floors at one minute; a fraction is refused here, not rounded")
        assertNull(parseIntervalMinutes("abc"))
        assertNull(parseIntervalMinutes(""))
    }

    @Test
    fun theOwnersNudgeSavesAsAScheduledPromptToThePm() {
        val draft = TriggerDraft(
            name = "nudge pm", kind = "schedule", intervalMinutes = "30", action = "prompt",
            agentId = "role:pm", prompt = "  check the board  ", projectId = "project-1",
        )
        assertNull(draft.problem())
        val save = draft.toSave(null)
        assertEquals("schedule", save.text("kind"))
        assertEquals(1_800_000L, save["intervalMs"]!!.jsonPrimitive.long)
        assertEquals("project-1", save.text("projectId"))
        assertEquals("true", save.text("enabled"))
        val action = save["action"]!!.jsonObject
        assertEquals(listOf("prompt", "role:pm", "check the board"), listOf(action.text("kind"), action.text("agentId"), action.text("prompt")))
        assertNull(save.text("id"))
    }

    @Test
    fun anyRoleAnyIntervalAnyProject() {
        val draft = TriggerDraft(
            name = "qa sweep", kind = "schedule", intervalMinutes = "7", action = "prompt",
            agentId = "role:qa", prompt = "re-run the flaky suite", projectId = "project-9",
        )
        assertNull(draft.problem())
        val save = draft.toSave(null)
        assertEquals(7 * 60_000L, save["intervalMs"]!!.jsonPrimitive.long)
        assertEquals("project-9", save.text("projectId"))
        assertEquals("role:qa", save["action"]!!.jsonObject.text("agentId"))
        assertEquals(10_000L * 60_000L, parseIntervalMinutes("10000"), "no upper preset")
        val toProfile = draft.copy(agentId = "agent-1234").toSave(null)
        assertEquals("agent-1234", toProfile["action"]!!.jsonObject.text("agentId"), "a specific profile is a target too")
    }

    @Test
    fun nothingIsPreset() {
        val blank = TriggerDraft.from(null, activeProjectId = null)
        assertEquals("", blank.intervalMinutes, "no default interval")
        assertNull(blank.agentId, "no default target")
        assertEquals("", blank.prompt)
        assertEquals("The interval is a whole number of minutes, at least 1.", blank.copy(name = "n", kind = "schedule").problem())
        assertEquals("Pick who receives the prompt.", blank.copy(name = "n", action = "prompt", prompt = "go").problem())
    }

    @Test
    fun aNewManualTriggerSendsWhatItAlwaysSent() {
        val save = TriggerDraft(name = "Pull next").toSave(null)
        assertEquals("manual", save.text("kind"))
        assertEquals("start_next", save["action"]!!.jsonObject.text("kind"))
        assertFalse("intervalMs" in save)
        assertFalse("projectId" in save, "a project only scopes a prompt")
        assertFalse("prompt" in save["action"]!!.jsonObject)
    }

    @Test
    fun problemsAreNamed() {
        assertNotNull(TriggerDraft().problem(), "a name is required")
        assertEquals(
            "The interval is a whole number of minutes, at least 1.",
            TriggerDraft(name = "n", kind = "schedule", intervalMinutes = "0").problem(),
        )
        assertEquals("Write the prompt to send.", TriggerDraft(name = "n", action = "prompt", agentId = "role:pm").problem())
        assertEquals("Pick who receives the prompt.", TriggerDraft(name = "n", action = "prompt", prompt = "go").problem())
        assertNotNull(TriggerDraft(name = "n", action = "prompt", agentId = "role:pm", prompt = "x".repeat(4001)).problem())
        assertNull(TriggerDraft(name = "n", kind = "manual", intervalMinutes = "0").problem(), "the interval only matters on a schedule")
    }

    @Test
    fun anEditKeepsKindIntervalAndProject() {
        val stored = TriggerView(
            id = "trg-1", name = "nudge pm", kind = "schedule", state = "armed", intervalMs = 45 * 60_000L,
            action = TriggerAction(kind = "prompt", agentId = "role:pm", prompt = "check the board"),
            projectId = "project-1", coalesceMs = 0, ceilingRuns = 6, enabled = true,
        )
        val draft = TriggerDraft.from(stored, activeProjectId = "project-2")
        val save = draft.copy(name = "renamed").toSave(stored.id)
        assertEquals("trg-1", save.text("id"))
        assertEquals("schedule", save.text("kind"))
        assertEquals(45 * 60_000L, save["intervalMs"]!!.jsonPrimitive.long)
        assertEquals("project-1", save.text("projectId"), "the stored project wins over the one on screen")
        assertEquals("check the board", save["action"]!!.jsonObject.text("prompt"))
    }

    @Test
    fun aDisabledTriggerEditsAsDisabled() {
        val stored = TriggerView(id = "t", name = "off", state = "disabled", enabled = false)
        assertFalse(TriggerDraft.from(stored, null).enabled)
        assertTrue(TriggerDraft.from(null, "project-1").enabled)
        assertEquals("project-1", TriggerDraft.from(null, "project-1").projectId)
    }
}
