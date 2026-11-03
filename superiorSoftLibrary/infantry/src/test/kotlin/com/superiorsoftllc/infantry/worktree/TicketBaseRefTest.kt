package com.superiorsoftllc.infantry.worktree

import kotlin.test.Test
import kotlin.test.assertEquals

/** BASE-1c: the ONE resolution point's precedence -- epic > project > INFANTRY_BASE_BRANCH > HEAD -- as the daemon builds it. */
class TicketBaseRefTest {

    /** The daemon's chain over settings held in plain maps (an absent key = no setting). */
    private fun chain(epics: Map<String, String>, projects: Map<String, String>, env: String?): TicketBaseRef =
        TicketBaseRef.epicFirst(
            epics::get,
            TicketBaseRef.projectFirst(projects::get, TicketBaseRef.fromEnv { if (it == TicketBaseRef.ENV) env else null }),
        )

    @Test
    fun allFourLevelsPresent_eachLevelWinsOnlyWhenEveryLevelAboveItIsUnset() {
        val scope = BaseScope(projectId = "p", epicId = "e")
        val all = chain(mapOf("e" to "release"), mapOf("p" to "develop"), env = "staging")
        assertEquals(BaseChoice("release", "epic e"), all.choose(scope), "1. the epic's base beats everything")
        assertEquals("release", all.refFor(scope))

        val noEpic = chain(emptyMap(), mapOf("p" to "develop"), env = "staging")
        assertEquals(BaseChoice("develop", "project base"), noEpic.choose(scope), "2. no epic setting: the project's base")

        val envOnly = chain(emptyMap(), emptyMap(), env = "staging")
        assertEquals(BaseChoice("staging", TicketBaseRef.ENV), envOnly.choose(scope), "3. no epic or project setting: INFANTRY_BASE_BRANCH")

        val none = chain(emptyMap(), emptyMap(), env = null)
        assertEquals(BaseChoice(TicketBaseRef.HEAD), none.choose(scope), "4. nothing set: HEAD")
    }

    @Test
    fun anEpicSettingOnlyAppliesToTicketsInThatEpic_andABlankSettingIsUnset() {
        val all = chain(mapOf("e" to "release", "blank" to "  "), mapOf("p" to "develop"), env = "staging")
        assertEquals(BaseChoice("develop", "project base"), all.choose(BaseScope("p", epicId = null)), "a ticket in no epic: the project's base")
        assertEquals(BaseChoice("develop", "project base"), all.choose(BaseScope("p", epicId = "other")), "another epic with no setting")
        assertEquals(BaseChoice("develop", "project base"), all.choose(BaseScope("p", epicId = "blank")), "a blank epic setting is unset")
        assertEquals(BaseChoice("release", "epic e"), all.choose(BaseScope(projectId = null, epicId = "e")))
    }

    /** BUG-BASE-087: the daemon's chain ends in NONE, so nothing configured is no base at all, never the main checkout's HEAD. */
    @Test
    fun theDaemonsChainEndsInNoneNotHead() {
        val daemon = TicketBaseRef.epicFirst(
            emptyMap<String, String>()::get,
            TicketBaseRef.projectFirst(mapOf("p" to "develop")::get, TicketBaseRef.fromEnv(orElse = TicketBaseRef.NONE) { null }),
        )
        assertEquals(BaseChoice(TicketBaseRef.NONE), daemon.choose(BaseScope(projectId = "q")), "no setting: none")
        assertEquals(BaseChoice("develop", "project base"), daemon.choose(BaseScope(projectId = "p")))
    }
}
