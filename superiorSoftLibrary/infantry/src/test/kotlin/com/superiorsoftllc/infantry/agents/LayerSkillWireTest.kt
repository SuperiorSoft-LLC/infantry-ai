package com.superiorsoftllc.infantry.agents

import com.superiorsoftllc.infantry.persistence.SettingRow
import com.superiorsoftllc.infantry.persistence.SqlInfantryStore
import com.superiorsoftllc.infantry.policy.ActionCatalog
import com.superiorsoftllc.infantry.protocol.Envelope
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LayerSkillWireTest {
    private val dir: File = Files.createTempDirectory("infantry-layerwire").toFile()
    private val db = SqlInfantryStore.open(File(dir, "a.db").absolutePath)
    private val pushed = mutableListOf<Envelope>()
    private val wire = LayerSkillWire(LayeredSkillStore(db)) { pushed += it }

    @AfterTest
    fun cleanUp() { runCatching { db.close() }; dir.deleteRecursively() }

    private fun send(type: String, json: String) = wire.handle(Envelope(type, json))
    private fun views(e: Envelope) = Json.decodeFromString(com.superiorsoftllc.infantry.protocol.LayerSkillList.serializer(), e.data).skills

    @Test
    fun saveListDeleteRoundTripPerScope() {
        assertNull(send(Envelope.LAYER_SKILL_SAVE, """{"scope":"global","name":"g","description":"d","body":"b"}"""))
        assertNull(send(Envelope.LAYER_SKILL_SAVE, """{"scope":"project","projectId":"p1","name":"pj","body":"b"}"""))
        assertEquals(listOf("g"), views(pushed[0]).map { it.name })
        assertEquals(listOf("pj"), views(send(Envelope.LAYER_SKILL_LIST, """{"scope":"project","projectId":"p1"}""")!!).map { it.name })
        assertTrue(views(send(Envelope.LAYER_SKILL_LIST, """{"scope":"project","projectId":"p2"}""")!!).isEmpty())
        assertNull(send(Envelope.LAYER_SKILL_DELETE, """{"scope":"global","name":"g"}"""))
        assertTrue(views(pushed.last()).isEmpty())
    }

    @Test
    fun refusalsAreErrors() {
        for (bad in listOf(
            Envelope.LAYER_SKILL_SAVE to """{"scope":"galaxy","name":"x","body":"b"}""",
            Envelope.LAYER_SKILL_SAVE to """{"scope":"project","name":"x","body":"b"}""",
            Envelope.LAYER_SKILL_SAVE to """{"scope":"global","name":"x","body":" "}""",
            Envelope.LAYER_SKILL_DELETE to """{"scope":"global","name":"nope"}""",
            Envelope.LAYER_SKILL_LIST to "not json",
        )) {
            val r = send(bad.first, bad.second)!!
            assertEquals(Envelope.ERROR, r.type)
            assertTrue(r.data.contains("refused"), r.data)
        }
        assertTrue(pushed.isEmpty())
    }

    @Test
    fun inlineCutoffSettingAndPolicy() {
        assertEquals(1_500, LayerSkillWire.inlineMax(db))
        db.settings.put(SettingRow("limits.skills.inlineMaxChars", "200", 1))
        assertEquals(200, LayerSkillWire.inlineMax(db))
        for (t in listOf(Envelope.LAYER_SKILL_SAVE, Envelope.LAYER_SKILL_LIST, Envelope.LAYER_SKILL_DELETE)) {
            assertTrue(ActionCatalog.ACTIONS.any { t in it.frames }, t)
        }
    }
}
