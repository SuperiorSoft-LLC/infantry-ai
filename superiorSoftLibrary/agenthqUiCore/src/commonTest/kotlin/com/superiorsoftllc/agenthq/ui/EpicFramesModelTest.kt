package com.superiorsoftllc.agenthq.ui

import com.superiorsoftllc.agenthq.ui.model.AppState
import com.superiorsoftllc.agenthq.ui.model.reduce
import com.superiorsoftllc.agenthq.ui.protocol.Epic
import com.superiorsoftllc.agenthq.ui.protocol.Envelope
import com.superiorsoftllc.agenthq.ui.protocol.WireRequests
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * UI-EPIC-1: the UI decodes the daemon's epic frames (contract 1.19.0 / EPIC-4) and keeps them per
 * project -- an `epics` frame replaces only the project it names, and a single `epic` frame upserts by
 * id -- and encodes the `epic_list` / `epic_save` requests the daemon's router answers.
 */
class EpicFramesModelTest {
    private val json = Json { ignoreUnknownKeys = true }
    private fun feed(vararg e: Envelope, from: AppState = AppState()) = e.fold(from) { s, f -> reduce(s, f, 100) }

    private fun epics(vararg x: Epic) =
        Envelope(Envelope.EPICS, json.encodeToString(ListSerializer(Epic.serializer()), x.toList()))

    private fun epic(x: Epic) = Envelope(Envelope.EPIC, json.encodeToString(Epic.serializer(), x))

    /**
     * (a) Each encoder produces the contract frame: type, and payload byte for byte. `epic_list` carries
     * the raw project id; `epic_save` sends projectId + name and omits the optional goal when absent.
     */
    @Test fun encodersProduceTheContractFrames() {
        val list = WireRequests.epicList("A")
        assertEquals(Envelope.EPIC_LIST, list.type, "epic_list names its frame")
        assertEquals("A", list.data, "epic_list carries the raw project id as payload")

        val create = WireRequests.epicSave("A", "Checkout")
        assertEquals(Envelope.EPIC_SAVE, create.type, "epic_save names its frame")
        assertEquals("""{"projectId":"A","name":"Checkout"}""", create.data, "a create sends projectId and name, omitting a null goal")

        val withGoal = WireRequests.epicSave("A", "Checkout", "ship it")
        assertEquals("""{"projectId":"A","name":"Checkout","goal":"ship it"}""", withGoal.data, "a goal is sent when given")
    }

    /**
     * (b) An `epics` frame is one project's slice: it replaces exactly the project it names and keeps
     * every other project's epics. Listing B after A keeps A's; a second A list replaces only A's.
     */
    @Test fun anEpicsFrameReplacesOnlyItsProject() {
        val afterA = feed(epics(Epic("E1", "A", "One"), Epic("E2", "A", "Two")))
        assertEquals(listOf("E1", "E2"), afterA.epics["A"]?.map { it.id }, "precondition: A's epics are loaded in order")

        val afterB = feed(epics(Epic("E3", "B", "One")), from = afterA)
        assertEquals(listOf("E1", "E2"), afterB.epics["A"]?.map { it.id }, "listing B keeps A's epics")
        assertEquals(listOf("E3"), afterB.epics["B"]?.map { it.id }, "B's epics are stored under B")

        val afterSecondA = feed(epics(Epic("E4", "A", "Three")), from = afterB)
        assertEquals(listOf("E4"), afterSecondA.epics["A"]?.map { it.id }, "a second A list replaces only A's epics")
        assertEquals(listOf("E3"), afterSecondA.epics["B"]?.map { it.id }, "B's epics survive A's replacement")
    }

    /** An empty `epics` frame names no project and clears the epics (mirrors the `workspaces` rule). */
    @Test fun anEmptyEpicsFrameClearsTheEpics() {
        val loaded = feed(epics(Epic("E1", "A", "One"), Epic("E2", "B", "Two")))
        assertEquals(2, loaded.epics.values.sumOf { it.size }, "precondition: two epics loaded")

        val cleared = feed(epics(), from = loaded)
        assertEquals(0, cleared.epics.size, "an empty epics frame clears the epics")
    }

    /**
     * (c) A single `epic` frame upserts by id inside its project: an unknown id is appended, a known id
     * is replaced in place (never duplicated), and the other project's epics are untouched.
     */
    @Test fun anEpicFrameUpsertsById() {
        val loaded = feed(epics(Epic("E1", "A", "One"), Epic("E2", "A", "Two")), epics(Epic("E9", "B", "Other")))

        val added = feed(epic(Epic("E3", "A", "Three")), from = loaded)
        assertEquals(listOf("E1", "E2", "E3"), added.epics["A"]?.map { it.id }, "an unknown id is appended to its project")

        val updated = feed(epic(Epic("E1", "A", "Renamed", storyIds = listOf("S1"))), from = added)
        assertEquals(listOf("E1", "E2", "E3"), updated.epics["A"]?.map { it.id }, "a known id is replaced, not duplicated")
        assertEquals("Renamed", updated.epics["A"]?.first { it.id == "E1" }?.name, "the epic's fields are updated")
        assertEquals(listOf("S1"), updated.epics["A"]?.first { it.id == "E1" }?.storyIds, "storyIds round-trip from the frame")
        assertEquals(listOf("E9"), updated.epics["B"]?.map { it.id }, "the other project's epics are untouched")
    }
}
