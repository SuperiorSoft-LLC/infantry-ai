package com.superiorsoftllc.infantry.mvp

import com.superiorsoftllc.infantry.daemon.DaemonFixture
import com.superiorsoftllc.infantry.protocol.Envelope
import com.superiorsoftllc.infantry.protocol.Project
import com.superiorsoftllc.infantry.protocol.ProjectSave
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CP-2 slice (a) on the WIRE: `project_save` carries a `directory`, the `project` / `projects`
 * answers echo it as a `directory` key, and a bad one is refused with the standing
 * `project_save refused: ...` error. Real daemon, real WebSocket, nothing faked but the provider.
 */
class ProjectDirectoryDaemonTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val timeoutMs = 4_000L

    private fun withDaemon(block: (DaemonFixture) -> Unit) {
        val fixture = DaemonFixture.launch()
        try {
            fixture.client.await(Envelope.READY)
            block(fixture)
        } finally {
            fixture.stop()
            fixture.deleteState()
        }
    }

    @Test
    fun aProjectSavedWithADirectoryAnswersWithItOnTheWire() = withDaemon { fixture ->
        val dir = Files.createTempDirectory("infantry-cp2-wire-").toFile()
        try {
            fixture.send(
                Envelope.PROJECT_SAVE,
                json.encodeToString(ProjectSave.serializer(), ProjectSave(name = "Wired", directory = dir.absolutePath)),
            )
            val frame = fixture.client.awaitWhere("project frame carrying the directory", timeoutMs) {
                it.type == Envelope.PROJECT && it.data.contains("Wired")
            }

            // The raw key, not just the decoded field: a client in another language reads exactly this.
            val raw = json.parseToJsonElement(frame.data).jsonObject
            assertEquals(dir.absolutePath, raw["directory"]?.jsonPrimitive?.content, "wire key 'directory': ${frame.data}")
            val project = json.decodeFromString(Project.serializer(), frame.data)

            // project_list echoes it too, so a client that only lists still learns the directory.
            fixture.send(Envelope.PROJECT_LIST, "")
            val listed = fixture.client.awaitWhere("projects frame carrying the directory", timeoutMs) {
                it.type == Envelope.PROJECTS && it.data.contains(project.id)
            }
            assertTrue(listed.data.contains("directory"), "projects list echoes the directory: ${listed.data}")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun aBadDirectoryIsRefusedOverTheWire() = withDaemon { fixture ->
        fixture.send(Envelope.PROJECT_SAVE, """{"name":"Bad","directory":"relative/path"}""")

        val refused = fixture.client.awaitWhere("project_save refusal", timeoutMs) {
            it.type == Envelope.ERROR && it.data.startsWith("project_save refused")
        }
        assertTrue(refused.data.contains("directory"), "the refusal names the field: ${refused.data}")
    }
}
