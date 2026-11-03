// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.mailbox

import com.superiorsoftllc.infantry.agents.AgentProfiles
import com.superiorsoftllc.infantry.daemon.bindSessionOnSpawn
import com.superiorsoftllc.infantry.persistence.AgentRepository
import com.superiorsoftllc.infantry.protocol.AgentProfile
import com.superiorsoftllc.infantry.protocol.SpawnOptions
import com.superiorsoftllc.infantry.session.SessionManager
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * XPROJ-1: `role@project` addressing (found, no live session, ambiguous, needs allowCrossProject), the sender-project origin the
 * `mail.incoming` hook sees, and the guard that nothing about other projects reaches a session's start brief.
 */
class CrossProjectAddressingTest {
    private class Repo : AgentRepository {
        private val m = linkedMapOf<String?, AgentProfile>()
        override fun save(profile: AgentProfile, at: Long) { m[profile.id] = profile }
        override fun get(id: String) = m[id]
        override fun all() = m.values.toList()
        override fun delete(id: String) = m.remove(id) != null
    }

    private class Rig(extraProjects: Int = 0) {
        val runner = ScriptedRunner(autoExit = true)
        val manager = SessionManager(FakeAgentProvider(), runner, tempSessionStore(), killGraceMs = 50)
        val mailbox = Mailbox(MemMailStore(), MailLimits())
        val agents = AgentProfiles(Repo(), manager)
        val refs = listOf(
            MailRouter.ProjectRef("SITE", "Website"), MailRouter.ProjectRef("APP", "Infantry AI"),
            MailRouter.ProjectRef("EMPTY", "Quiet"), MailRouter.ProjectRef("D1", "Twin"), MailRouter.ProjectRef("D2", "twin"),
        ) + (1..extraProjects).map { MailRouter.ProjectRef("X$it", "Filler $it") }
        val router = MailRouter(manager, mailbox, agents, projectRefs = { refs }).also { r -> manager.eventSink = { e -> r.onSessionEvent(e) } }

        init {
            for ((id, role, project) in listOf(
                Triple("site-pm", "pm", "SITE"), Triple("app-pm", "pm", "APP"), Triple("app-dev1", "backend", "APP"), Triple("app-dev2", "backend", "APP"),
            )) agents.save(AgentProfile(id = id, name = id, role = role, projectId = project), requireProject = true)
        }

        fun bind(agentId: String) = bindSessionOnSpawn(manager, agents, SpawnOptions(agentId = agentId), null)!!
    }

    private fun send(rig: Rig, from: String, to: String, cross: Boolean = true) =
        rig.router.send(MailSendRequest(from = from, to = to, body = "hello", allowCrossProject = cross))

    @Test
    fun role_at_project_resolves_by_id_and_by_name_to_that_projects_session() {
        val rig = Rig()
        val site = rig.bind("site-pm")
        val appPm = rig.bind("app-pm")
        for (to in listOf("pm@APP", "pm@Infantry AI", "pm@infantry ai")) {
            val sent = send(rig, site.id, to) as MailResult.Sent
            assertEquals(appPm.id, sent.message.to, "resolved to the session for $to")
        }
    }

    @Test
    fun role_at_other_project_is_refused_without_allowCrossProject() {
        val rig = Rig()
        val site = rig.bind("site-pm")
        rig.bind("app-pm")
        val refused = send(rig, site.id, "pm@APP", cross = false) as MailResult.Refused
        assertTrue("allowCrossProject" in refused.reason && "APP" in refused.reason && "SITE" in refused.reason, refused.reason)
    }

    @Test
    fun role_at_project_with_no_live_session_ambiguous_role_unknown_and_ambiguous_name_are_clear_errors() {
        val rig = Rig()
        val site = rig.bind("site-pm")
        rig.bind("app-dev1"); rig.bind("app-dev2")
        assertTrue("no live pm session in project APP" in (send(rig, site.id, "pm@APP") as MailResult.Refused).reason)
        assertTrue("no live pm session in project EMPTY" in (send(rig, site.id, "pm@Quiet") as MailResult.Refused).reason)
        val many = (send(rig, site.id, "backend@APP") as MailResult.Refused).reason
        assertTrue("multiple backend agents" in many && "app-dev1" in many && "app-dev2" in many, many)
        assertTrue("no project 'Nowhere'" in (send(rig, site.id, "pm@Nowhere") as MailResult.Refused).reason)
        val twin = (send(rig, site.id, "pm@twin") as MailResult.Refused).reason
        assertTrue("ambiguous" in twin && "D1" in twin && "D2" in twin, twin)
    }

    @Test
    fun the_incoming_hook_sees_the_senders_project_only_for_cross_project_mail() {
        val rig = Rig()
        val site = rig.bind("site-pm")
        val appPm = rig.bind("app-pm")
        val seen = mutableListOf<MailRouter.IncomingMailInfo>()
        rig.router.onIncoming = { i -> seen += i; null }
        send(rig, site.id, "pm@APP")
        rig.router.send(MailSendRequest(from = "user", to = appPm.id, body = "local"))
        val cross = seen.first { it.message.from == site.id }
        assertEquals("SITE", cross.fromProjectId)
        assertEquals("Website", cross.fromProjectName)
        assertEquals(appPm.id, cross.recipientSessionId)
        assertEquals(null, seen.first { it.message.from == "user" }.fromProjectId)
    }

    @Test
    fun the_start_brief_does_not_grow_with_the_project_count() {
        fun firstPrompt(extra: Int): String {
            val rig = Rig(extra)
            val site = rig.bind("site-pm")
            val appPm = rig.bind("app-pm")
            send(rig, site.id, appPm.id)
            return rig.runner.processes.last().input.toString()
        }
        val few = firstPrompt(0)
        val many = firstPrompt(40)
        assertNotNull(few)
        assertEquals(few.length, many.length, "a session's start text must not depend on how many projects exist")
        assertTrue("Filler" !in many)
    }

    @Test
    fun session_start_composition_never_reads_the_project_directory() {
        // Structural half of the guard: the code that composes a start brief names no project directory lookup.
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "infantryCore/src/main").isDirectory }
        val offenders = File(root, "infantryCore/src/main/kotlin/com/superiorsoftllc/infantry/agents").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { f -> f.readText().let { "infantry_projects" in it || "ProjectRef" in it || "projects.list" in it } }
            .map { it.name }.toList()
        assertTrue(offenders.isEmpty(), "start-brief code must not list projects: $offenders")
    }
}
