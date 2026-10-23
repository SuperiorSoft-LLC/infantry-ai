package com.superiorsoftllc.agenthq.cli.mcp

import com.superiorsoftllc.agenthq.session.SessionRecord
import com.superiorsoftllc.agenthq.session.SessionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** SCOPE-1 (contract 1.106.0): the default scope is the caller's project; 'all' and foreign ids are for pm / owner. */
class ProjectScopeTest {
    private fun session(id: String, role: String, project: String) = SessionRecord(
        id = id, provider = "claude", workingDir = ".", spawnedAt = 0, lastHeartbeat = 0,
        state = SessionState.IDLE, role = role, projectId = project,
    )

    private val sessions = listOf(session("s-fe", "frontend", "P1"), session("s-pm", "pm", "P1"), session("s-ow", "owner", "P2"))
    private fun env(id: String?, project: String? = null): (String) -> String? = {
        when (it) {
            "AGENTHQ_SESSION_ID" -> id
            "AGENTHQ_PROJECT_ID" -> project
            else -> null
        }
    }

    @Test fun `no argument scopes to the callers own project`() {
        assertEquals("P1", ProjectScope.resolve(null, sessions, env("s-fe"), null).projectId)
        assertEquals("P1", ProjectScope.resolve(null, sessions, env("s-pm"), "OTHER").projectId)
        assertEquals("project:P1", ProjectScope.resolve(null, sessions, env("s-fe"), null).label)
    }

    @Test fun `all is refused for a non pm role and allowed for pm and owner`() {
        val refused = ProjectScope.resolve("all", sessions, env("s-fe"), null)
        assertNotNull(refused.refusal)
        assertEquals("P1", refused.projectId)
        assertNull(ProjectScope.resolve("all", sessions, env("s-pm"), null).projectId)
        assertEquals("all", ProjectScope.resolve("all", sessions, env("s-ow"), null).label)
    }

    @Test fun `a foreign project id is refused for a non pm role only`() {
        assertNotNull(ProjectScope.resolve("P2", sessions, env("s-fe"), null).refusal)
        assertNull(ProjectScope.resolve("P1", sessions, env("s-fe"), null).refusal)
        assertEquals("P2", ProjectScope.resolve("P2", sessions, env("s-pm"), null).projectId)
    }

    @Test fun `role comes from the daemon record, not the environment`() {
        // An unknown session id is not privileged, whatever else the environment claims.
        val scope = ProjectScope.resolve("all", sessions, env("ghost", "P1"), null)
        assertTrue(scope.refusal != null)
    }

    @Test fun `an unknown session id is refused all, a foreign project and foreign objects`() {
        val ghost = env("ghost", "P1")
        assertNotNull(ProjectScope.resolve("all", sessions, ghost, null).refusal)
        assertNotNull(ProjectScope.resolve("P2", sessions, ghost, null).refusal)
        assertNull(ProjectScope.resolve("P1", sessions, ghost, null).refusal)
        val caller = ProjectScope.callerOf(sessions, ghost)
        assertNull(caller)
        assertNotNull(ProjectScope.foreignRefusal("ticket abc", "P2", caller, ghost))
        assertNull(ProjectScope.foreignRefusal("ticket abc", "P1", caller, ghost))
    }

    @Test fun `no session id keeps the owner CLI path unchanged`() {
        assertNull(ProjectScope.resolve(null, sessions, env(null), null).projectId)
        assertEquals("P9", ProjectScope.resolve(null, sessions, env(null), "P9").projectId)
        assertEquals("P2", ProjectScope.resolve("P2", sessions, env(null), "P9").projectId)
        assertNull(ProjectScope.resolve("all", sessions, env(null), "P9").projectId)
    }

    @Test fun `foreign object refusal covers non pm session callers only`() {
        val fe = ProjectScope.callerOf(sessions, env("s-fe"))
        assertNotNull(ProjectScope.foreignRefusal("ticket abc", "P2", fe, env("s-fe")))
        assertNull(ProjectScope.foreignRefusal("ticket abc", "P1", fe, env("s-fe")))
        val pm = ProjectScope.callerOf(sessions, env("s-pm"))
        assertNull(ProjectScope.foreignRefusal("ticket abc", "P2", pm, env("s-pm")))
        assertNull(ProjectScope.foreignRefusal("ticket abc", "P2", null, env(null)))
    }
}
