package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.ui.model.AppState
import com.superiorsoftllc.infantry.ui.model.TraitDraft
import com.superiorsoftllc.infantry.ui.model.bindTrait
import com.superiorsoftllc.infantry.ui.model.moveTrait
import com.superiorsoftllc.infantry.ui.model.reduce
import com.superiorsoftllc.infantry.ui.model.rolesBinding
import com.superiorsoftllc.infantry.ui.model.traitIdsFor
import com.superiorsoftllc.infantry.ui.model.traitsFor
import com.superiorsoftllc.infantry.ui.model.unbindTrait
import com.superiorsoftllc.infantry.ui.model.withTraitsRequested
import com.superiorsoftllc.infantry.ui.protocol.Envelope
import com.superiorsoftllc.infantry.ui.protocol.RoleTraits
import com.superiorsoftllc.infantry.ui.protocol.TraitCheck
import com.superiorsoftllc.infantry.ui.protocol.TraitView
import com.superiorsoftllc.infantry.ui.protocol.WireRequests
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * TRAITS-T8 (docs/INFANTRY-ROLE-TRAITS.md section 6): the Roles tab trait editor is rendered from the existing
 * T1/T2 frames only (`traits`, `trait_item`, `role_traits`, `role_traits_all`) and sends the existing requests.
 */
class TraitEditorModelTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private fun feed(vararg e: Envelope, from: AppState = AppState()) = e.fold(from) { s, f -> reduce(s, f, 100) }

    private val reportBack = TraitView(
        "report-back", "REPORT BACK", 1, "Mail the PM.",
        TraitCheck("report", "pm", listOf("commit", "tests"), true), 1, 1,
    )
    private val terse = TraitView("terse", "Terse", 1, "Be brief.", null, 2, 2)

    private fun traits(vararg t: TraitView) = Envelope(Envelope.TRAITS, json.encodeToString(ListSerializer(TraitView.serializer()), t.toList()))
    private fun item(t: TraitView) = Envelope(Envelope.TRAIT_ITEM, json.encodeToString(TraitView.serializer(), t))
    private fun roleTraits(roleId: String, vararg ids: String) =
        Envelope(Envelope.ROLE_TRAITS, json.encodeToString(RoleTraits.serializer(), RoleTraits(roleId, ids.toList(), 5)))
    private fun allRoleTraits(vararg r: RoleTraits) =
        Envelope(Envelope.ROLE_TRAITS_ALL, json.encodeToString(ListSerializer(RoleTraits.serializer()), r.toList()))
    private fun error(text: String) = Envelope(Envelope.ERROR, text)

    @Test fun `load - traits is the whole library ordered by name, and it ends the loading state`() {
        val s = feed(traits(terse, reportBack), from = AppState().withTraitsRequested())
        assertEquals(listOf("report-back", "terse"), s.traits.map { it.id })
        assertFalse(s.traitsLoading)
        assertEquals(TraitCheck("report", "pm", listOf("commit", "tests"), true), s.traits.first().check)
        val all = feed(allRoleTraits(RoleTraits("backend", listOf("terse", "report-back")), RoleTraits("qa", listOf("report-back"))), from = s)
        assertEquals(listOf("terse", "report-back"), all.traitIdsFor("backend"))
        assertEquals(listOf("Terse", "REPORT BACK"), all.traitsFor("backend").map { it.name }, "binding order, not name order")
        assertEquals(listOf("backend", "qa"), all.rolesBinding("report-back"))
    }

    @Test fun `create - a pushed trait_item joins the library in name order`() {
        val s = feed(traits(terse), item(reportBack))
        assertEquals(listOf("report-back", "terse"), s.traits.map { it.id })
    }

    @Test fun `edit - a trait_item with a known id replaces it in place, version included`() {
        val edited = reportBack.copy(name = "REPORT BACK v2", body = "Mail the PM, always.", version = 2)
        val s = feed(traits(reportBack, terse), item(edited))
        assertEquals(2, s.traits.size, "an edit never duplicates the trait")
        assertEquals(edited, s.traits.single { it.id == "report-back" })
    }

    @Test fun `delete - the pushed traits list replaces the library`() {
        val s = feed(traits(reportBack, terse), traits(terse))
        assertEquals(listOf("terse"), s.traits.map { it.id })
    }

    @Test fun `bind - a pushed role_traits sets that role's ordered list and leaves the others`() {
        val s = feed(roleTraits("qa", "report-back"), roleTraits("backend", "report-back"), roleTraits("backend", "report-back", "terse"))
        assertEquals(listOf("report-back", "terse"), s.traitIdsFor("backend"))
        assertEquals(listOf("report-back"), s.traitIdsFor("qa"))
    }

    @Test fun `unbind - a pushed role_traits without the id drops it, and an empty list removes the role`() {
        val bound = feed(roleTraits("backend", "report-back", "terse"))
        val one = feed(roleTraits("backend", "terse"), from = bound)
        assertEquals(listOf("terse"), one.traitIdsFor("backend"))
        val none = feed(roleTraits("backend"), from = one)
        assertEquals(emptyList(), none.traitIdsFor("backend"))
        assertFalse("backend" in none.roleTraits, "a role with no trait left is not kept as an empty entry")
    }

    @Test fun `an error frame for a trait request leaves the library and the bindings unchanged`() {
        val before = feed(traits(reportBack, terse), roleTraits("backend", "report-back"))
        for (refusal in listOf(
            "trait_save refused: body is over 2048 bytes",
            "trait_delete refused: trait 'report-back' is bound to role 'backend'",
            "role_traits_set refused: unknown trait 'nope'",
        )) {
            val after = feed(error(refusal), from = before.withTraitsRequested())
            assertEquals(before.traits, after.traits, refusal)
            assertEquals(before.roleTraits, after.roleTraits, refusal)
            assertEquals(refusal, after.traitError)
            assertFalse(after.traitsLoading, "a refusal ends the loading state")
        }
    }

    @Test fun `an old daemon with no trait frames gives an empty library, no crash and no notice`() {
        val asked = AppState().withTraitsRequested()
        val s = feed(error("unknown frame type 'trait_list'"), error("unknown frame type 'role_traits_list'"), from = asked)
        assertEquals(emptyList(), s.traits)
        assertEquals(emptyMap(), s.roleTraits)
        assertFalse(s.traitsLoading)
        assertNull(s.traitError)
        assertTrue(s.notices.isEmpty(), "an old daemon is not an error worth a notice on every connect")
        assertEquals(emptyList(), AppState().traitsFor("backend"))
    }

    @Test fun `a later answer clears the last refusal`() {
        val refused = feed(error("trait_save refused: name is blank"))
        assertNotNull(refused.traitError)
        assertNull(feed(item(terse), from = refused).traitError)
    }

    @Test fun `bind, unbind and reorder build the whole list role_traits_set sends`() {
        assertEquals(listOf("a", "b"), bindTrait(listOf("a"), "b"))
        assertEquals(listOf("a", "b"), bindTrait(listOf("a", "b"), "a"), "binding twice changes nothing")
        assertEquals(listOf("b"), unbindTrait(listOf("a", "b"), "a"))
        assertEquals(emptyList(), unbindTrait(listOf("a"), "a"), "[] clears the role")
        assertEquals(listOf("b", "a", "c"), moveTrait(listOf("a", "b", "c"), "b", -1))
        assertEquals(listOf("a", "b", "c"), moveTrait(listOf("a", "b", "c"), "a", -1), "unchanged at the edge")
        val frame = WireRequests.roleTraitsSet("backend", emptyList())
        assertEquals(Envelope.ROLE_TRAITS_SET, frame.type)
        assertEquals("""{"roleId":"backend","traitIds":[]}""", frame.data)
    }

    @Test fun `the editor draft round-trips a stored trait and refuses what the daemon would refuse`() {
        val draft = TraitDraft.of(reportBack)
        assertFalse(draft.isNew)
        assertEquals("commit, tests", draft.fieldsText)
        assertNull(draft.problem())
        assertEquals(reportBack.check, draft.toSave().check)
        val noCheck = TraitDraft(id = "terse", name = "Terse", body = "Be brief.")
        val payload = Json.parseToJsonElement(WireRequests.traitSave(noCheck.toSave()).data).jsonObject
        assertFalse("check" in payload, "no check is omitted, never sent as null")
        assertNotNull(TraitDraft(id = "Bad Id", name = "x", body = "y").problem())
        assertNotNull(noCheck.copy(body = "x".repeat(2049)).problem())
        assertNotNull(noCheck.copy(checkOn = true, fieldsText = "").problem())
        assertNotNull(noCheck.copy(checkOn = true, fieldsText = "commit, Commit").problem())
    }
}
