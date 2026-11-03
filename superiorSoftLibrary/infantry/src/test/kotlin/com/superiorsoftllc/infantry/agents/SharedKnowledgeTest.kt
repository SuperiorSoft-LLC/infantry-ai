// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.agents

import com.superiorsoftllc.infantry.mailbox.FakeAgentProvider
import com.superiorsoftllc.infantry.mailbox.ScriptedRunner
import com.superiorsoftllc.infantry.mailbox.tempSessionStore
import com.superiorsoftllc.infantry.persistence.SqlInfantryStore
import com.superiorsoftllc.infantry.plugins.official.roles.daemon.RoleCatalog
import com.superiorsoftllc.infantry.session.SessionManager
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The shipped KNOWLEDGE.md teaches every role the progress tool and reaches every role instance. */
class SharedKnowledgeTest {
    @Test
    fun `knowledge is shipped and names every progress mechanism`() {
        val t = SharedKnowledge.text
        assertTrue(t.isNotBlank())
        for (m in listOf("infantry run", "##infantry", "infantry_progress", "plan", "step", "count", "percent", "estimate", "done", "fail", "Gradle", "browser")) {
            assertTrue(t.contains(m, ignoreCase = true), "KNOWLEDGE.md must mention '$m'")
        }
    }

    @Test
    fun `qa persona points at the shared progress rules`() {
        val qa = RoleCatalog.load().persona("qa")!!
        assertTrue(qa.contains("KNOWLEDGE") && qa.contains("progress"))
    }

    @Test
    fun `instances of different roles carry it even over a persona override`() {
        val dir: File = Files.createTempDirectory("infantry-sk").toFile()
        val store = SqlInfantryStore.open(File(dir, "infantry.db").absolutePath)
        try {
            val manager = SessionManager(FakeAgentProvider(), ScriptedRunner(autoExit = false), tempSessionStore(), killGraceMs = 50, defaultWorkingDir = dir)
            val catalog = RoleCatalog.load()
            val profiles = AgentProfiles(store.agents, manager, rolePersona = { id -> if (id == "frontend") "OVERRIDE" else catalog.persona(id) })
            for (role in listOf("qa", "frontend")) {
                val ins = profiles.instanceFor(null, role)!!.instructions!!
                assertTrue(ins.contains(SharedKnowledge.textFor(role)), "$role must carry the knowledge")
            }
            assertTrue(profiles.instanceFor(null, "frontend")!!.instructions!!.startsWith("OVERRIDE"))
            assertEquals(1, Regex("## PROGRESS").findAll(profiles.instanceFor(null, "qa")!!.instructions!!).count())
        } finally { store.close(); dir.deleteRecursively() }
    }

    @Test
    fun `a section marked skip for a role is dropped for that role only and markers never show`() {
        assertTrue(SharedKnowledge.textFor("backend").contains("## PROGRESS"))
        assertTrue(SharedKnowledge.textFor(null).contains("## PROGRESS"))
        val pm = SharedKnowledge.textFor("pm")
        assertTrue(!pm.contains("## PROGRESS") && !pm.contains("## TO-DO LIST") && pm.contains("## ASK"))
        assertTrue(!SharedKnowledge.textFor("backend").contains("skip:"))
    }
}
