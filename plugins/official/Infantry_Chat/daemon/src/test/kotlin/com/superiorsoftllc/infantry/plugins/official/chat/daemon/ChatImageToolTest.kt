package com.superiorsoftllc.infantry.plugins.official.chat.daemon

import com.superiorsoftllc.infantry.daemon.api.Caller
import com.superiorsoftllc.infantry.daemon.api.Frame
import com.superiorsoftllc.infantry.daemon.api.ToolCall
import com.superiorsoftllc.infantry.daemon.api.ToolInvoker
import com.superiorsoftllc.infantry.daemon.api.ToolResult
import com.superiorsoftllc.infantry.daemon.api.files.DirectoryProjectFiles
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.nio.file.Files
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** CHAT-IMAGE-1: the tool stores and posts; refuses paths outside the project, wrong types and oversize files. */
class ChatImageToolTest {
    private val dirs = mutableListOf<File>()
    private val posted = mutableListOf<JsonObject>()
    @AfterTest fun clean() { dirs.forEach { it.deleteRecursively() }; dirs.clear() }

    private val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==")

    private fun setup(): Pair<File, ChatImageTool> {
        val root = Files.createTempDirectory("infantry-chat-tool").toFile().also { dirs += it }
        val project = File(root, "proj").also { it.mkdirs() }
        val tool = ChatImageTool({ if (it == "s1") "p1" else null }, { project.path }, { DirectoryProjectFiles(project) }, { args, _ -> posted += args; ToolResult("noted") })
        return root to tool
    }

    private fun call(json: String) = object : ToolCall {
        override val arguments: JsonObject = Json.parseToJsonElement(json).jsonObject
        override val caller = Caller(owner = false, agent = true)
        override val clientSessionId: String = "s1"
        override val clientProjectId: String = "p1"
        override fun request(type: String, data: String, sessionId: String?, timeoutMs: Long): Frame = error("n/a")
    }

    @Test fun `a project image is stored under chat uploads and posted with its data`() {
        val (root, tool) = setup()
        File(root, "proj/shot.png").writeBytes(png)
        val r = tool.handle(call("""{"path":"shot.png","caption":"login screen"}"""))
        assertTrue(!r.isError, r.text)
        val p = posted.single()
        assertEquals("login screen", p["text"].toString().trim('"'))
        val stored = p["storedImage"].toString().trim('"').replace("\\\\", "/")
        assertTrue("/.infantry/uploads/chat/" in stored && File(stored).isFile, stored)
        assertNotNull(p["imageData"])
    }

    @Test fun `a path outside the project is refused`() {
        val (root, tool) = setup()
        File(root, "secret.png").writeBytes(png)
        assertTrue(tool.handle(call("""{"path":"../secret.png"}""")).isError)
        assertTrue(tool.handle(call("""{"path":${Json.encodeToString(kotlinx.serialization.serializer<String>(), File(root, "secret.png").path)}}""")).isError)
        assertTrue(posted.isEmpty())
    }

    @Test fun `a non image and an oversize file are refused`() {
        val (root, tool) = setup()
        File(root, "proj/a.png").writeText("not an image")
        File(root, "proj/big.png").writeBytes(png + ByteArray(ImageUpload.MAX_BYTES + 1))
        assertTrue(tool.handle(call("""{"path":"a.png"}""")).isError)
        assertTrue(tool.handle(call("""{"path":"big.png"}""")).text.contains("limit"))
        assertTrue(posted.isEmpty())
    }
}
