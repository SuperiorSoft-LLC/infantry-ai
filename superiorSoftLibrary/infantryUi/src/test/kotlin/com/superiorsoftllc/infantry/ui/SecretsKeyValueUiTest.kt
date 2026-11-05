// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.plugins.official.run.*

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.infantry.ui.model.UiFlags
import com.superiorsoftllc.infantry.ui.protocol.SecretEntryRow
import com.superiorsoftllc.infantry.ui.protocol.SecretFieldRow
import com.superiorsoftllc.infantry.ui.protocol.SecretRow
import com.superiorsoftllc.infantry.ui.protocol.SecretState
import kotlin.test.Test
import kotlin.test.assertEquals

/** SECRETS-2 (ticket e5d1b57f): Configuration > Secrets lists the user's key/value secrets and edits them; a value never shows. */
@OptIn(ExperimentalTestApi::class)
class SecretsKeyValueUiTest {
    private val pw = "store-pass-0123456789"
    private val state = SecretState(
        "os",
        listOf(SecretRow("update-token", "Update token")),
        listOf(SecretEntryRow("android-signing", listOf(SecretFieldRow("keyAlias", "••••"), SecretFieldRow("storePassword", "••••6789")))),
    )

    private class Calls {
        val set = mutableListOf<Triple<String, String, String>>()
        val deletedFields = mutableListOf<Pair<String, String>>()
        val deleted = mutableListOf<String>()
        fun actions() = SecretActions(
            set = { _, _ -> }, clear = {}, import = {},
            setField = { n, f, v -> set += Triple(n, f, v) },
            deleteField = { n, f -> deletedFields += n to f },
            delete = { deleted += it },
        )
    }

    private fun androidx.compose.ui.test.ComposeUiTest.show(s: SecretState, calls: Calls) = setContent {
        ConfigDialog(UiFlags(), onFlags = {}, onDismiss = {}, secrets = s, secretActions = calls.actions(), initialTab = ConfigTab.SECRETS)
    }

    @Test fun `lists each secret's fields with their masked tails, never a value`() = runDesktopComposeUiTest(1000, 1200) {
        show(state, Calls())
        onNodeWithTag("$SECRET_ENTRY_TAG-android-signing").assertExists()
        onNodeWithTag("$SECRET_FIELD_TAG-android-signing-storePassword").assertExists()
        assertEquals(1, onAllNodesWithText("••••6789").fetchSemanticsNodes().size)
        assertEquals(0, onAllNodesWithText(pw, substring = true).fetchSemanticsNodes().size)
    }

    @Test fun `adds a secret, a field, replaces and deletes a field`() = runDesktopComposeUiTest(1000, 1400) {
        val calls = Calls()
        show(state, calls)
        onNodeWithTag(SECRET_NEW_SAVE_TAG).performScrollTo().assertIsNotEnabled()
        onNodeWithTag(SECRET_NEW_NAME_TAG).performTextInput("deploy")
        onNodeWithTag(SECRET_NEW_FIELD_TAG).performTextInput("DEPLOY_TOKEN")
        onNodeWithTag(SECRET_NEW_VALUE_TAG).performTextInput("tok-abc")
        onNodeWithTag(SECRET_NEW_SAVE_TAG).assertIsEnabled().performClick()
        assertEquals(Triple("deploy", "DEPLOY_TOKEN", "tok-abc"), calls.set.last())
        assertEquals(0, onAllNodesWithText("tok-abc", substring = true).fetchSemanticsNodes().size, "the typed value is masked")

        onNodeWithTag("$SECRET_ADD_FIELD_NAME_TAG-android-signing").performScrollTo().performTextInput("keyPassword")
        onNodeWithTag("$SECRET_ADD_FIELD_VALUE_TAG-android-signing").performTextInput("kp-secret")
        onNodeWithTag("$SECRET_ADD_FIELD_TAG-android-signing").performClick()
        assertEquals(Triple("android-signing", "keyPassword", "kp-secret"), calls.set.last())

        onNodeWithTag("$SECRET_FIELD_REPLACE_TAG-android-signing-keyAlias").performScrollTo().performClick()
        onNodeWithTag("$SECRET_FIELD_INPUT_TAG-android-signing-keyAlias").performTextInput("new-alias")
        onNodeWithTag("$SECRET_FIELD_SAVE_TAG-android-signing-keyAlias").performClick()
        assertEquals(Triple("android-signing", "keyAlias", "new-alias"), calls.set.last())

        onNodeWithTag("$SECRET_FIELD_DELETE_TAG-android-signing-storePassword").performScrollTo().performClick()
        assertEquals("android-signing" to "storePassword", calls.deletedFields.single())
    }

    @Test fun `a bad or taken name cannot be added`() = runDesktopComposeUiTest(1000, 1400) {
        show(state, Calls())
        onNodeWithTag(SECRET_NEW_NAME_TAG).performScrollTo().performTextInput("Bad Name")
        onNodeWithTag(SECRET_NEW_FIELD_TAG).performTextInput("A")
        onNodeWithTag(SECRET_NEW_VALUE_TAG).performTextInput("value-1")
        onNodeWithTag(SECRET_NEW_SAVE_TAG).assertIsNotEnabled()
        onNodeWithTag("$SECRET_ADD_FIELD_NAME_TAG-android-signing").performScrollTo().performTextInput("keyAlias")
        onNodeWithTag("$SECRET_ADD_FIELD_VALUE_TAG-android-signing").performTextInput("x-y-z")
        onNodeWithTag("$SECRET_ADD_FIELD_TAG-android-signing").assertIsNotEnabled()
    }

    @Test fun `deleting a secret asks first`() = runDesktopComposeUiTest(1000, 1200) {
        val calls = Calls()
        show(state, calls)
        onNodeWithTag("$SECRET_ENTRY_DELETE_TAG-android-signing").performScrollTo().performClick()
        assertEquals(emptyList(), calls.deleted)
        onNodeWithTag("$SECRET_ENTRY_DELETE_CONFIRM_TAG-android-signing").performClick()
        assertEquals(listOf("android-signing"), calls.deleted)
    }

    @Test fun `the demo lock shows the secrets but offers no change`() = runDesktopComposeUiTest(1000, 1200) {
        show(state.copy(locked = true), Calls())
        onNodeWithTag(SECRET_LOCKED_TAG).assertExists()
        onNodeWithTag("$SECRET_FIELD_TAG-android-signing-storePassword").assertExists()
        onNodeWithTag(SECRET_NEW_TAG).assertDoesNotExist()
        onNodeWithTag("$SECRET_ENTRY_DELETE_TAG-android-signing").assertDoesNotExist()
        onNodeWithTag("$SECRET_FIELD_DELETE_TAG-android-signing-storePassword").assertDoesNotExist()
    }

    @Test fun `an older daemon shows only the built-in secrets`() = runDesktopComposeUiTest(1000, 1200) {
        setContent {
            ConfigDialog(
                UiFlags(), onFlags = {}, onDismiss = {}, secrets = state,
                secretActions = SecretActions(set = { _, _ -> }, clear = {}, import = {}), initialTab = ConfigTab.SECRETS,
            )
        }
        onNodeWithTag("$SECRET_INPUT_TAG-update-token").assertExists()
        onNodeWithTag(SECRET_ENTRIES_TAG).assertDoesNotExist()
    }

    // SECRETS-2b: scopes.

    @Test fun `app secrets carry the App badge, a project's own stay out of the app list, and a note counts them`() = runDesktopComposeUiTest(1000, 1200) {
        val withProject = state.copy(entries = state.entries + SecretEntryRow("deploy", listOf(SecretFieldRow("TOKEN", "••••abcd")), project = "p1"))
        show(withProject, Calls())
        onNodeWithTag("$SECRET_SCOPE_TAG-android-signing").assertExists()
        assertEquals(1, onAllNodesWithText(SecretForm.SCOPE_APP).fetchSemanticsNodes().size)
        onNodeWithTag("$SECRET_ENTRY_TAG-deploy").assertDoesNotExist()
        onNodeWithTag(SECRET_PROJECTS_NOTE_TAG).assertExists()
    }

    @Test fun `no secret yet shows the one-line explanation`() = runDesktopComposeUiTest(1000, 1200) {
        show(state.copy(entries = emptyList()), Calls())
        onNodeWithTag(SECRET_EMPTY_TAG).assertExists()
        onNodeWithTag(SECRET_NEW_TAG).assertExists()
    }

    @Test fun `an app secret moves into the project on screen, and a name the project overrides says so`() = runDesktopComposeUiTest(1000, 1200) {
        val moves = mutableListOf<Triple<String, String?, String?>>()
        val withShadow = state.copy(entries = state.entries + SecretEntryRow("android-signing", listOf(SecretFieldRow("keyAlias", "••••")), project = "p1"))
        setContent {
            ConfigDialog(
                UiFlags(), onFlags = {}, onDismiss = {}, secrets = withShadow, initialTab = ConfigTab.SECRETS,
                secretActions = SecretActions(
                    set = { _, _ -> }, clear = {}, import = {}, setField = { _, _, _ -> }, deleteField = { _, _ -> }, delete = {},
                    move = { n, from, to -> moves += Triple(n, from, to) }, currentProject = SecretProjectRef("p1", "Site"),
                ),
            )
        }
        onNodeWithTag("$SECRET_NOTE_TAG-android-signing").assertExists()
        onNodeWithTag("$SECRET_MOVE_TAG-android-signing").performScrollTo().performClick()
        assertEquals(Triple("android-signing", null, "p1"), moves.single())
    }

    @Test fun `with no project on screen there is no move`() = runDesktopComposeUiTest(1000, 1200) {
        setContent {
            ConfigDialog(
                UiFlags(), onFlags = {}, onDismiss = {}, secrets = state, initialTab = ConfigTab.SECRETS,
                secretActions = SecretActions(set = { _, _ -> }, clear = {}, import = {}, setField = { _, _, _ -> }, move = { _, _, _ -> }),
            )
        }
        onNodeWithTag("$SECRET_MOVE_TAG-android-signing").assertDoesNotExist()
    }

    @Test fun `a project's editor shows its own secrets with the Project badge and moves one to the app`() = runDesktopComposeUiTest(1000, 1000) {
        val moved = mutableListOf<String>()
        setContent {
            InfantryTheme {
                SecretsScopeEditor(
                    entries = listOf(SecretEntryRow("deploy", listOf(SecretFieldRow("TOKEN", "••••abcd")), project = "p1")),
                    locked = false, title = "Site secrets", hint = SecretForm.PROJECT_APPLIES_TO, scopeBadge = SecretForm.SCOPE_PROJECT,
                    actions = SecretScopeActions(setField = { _, _, _ -> }, move = { moved += it }, moveLabel = "Move to app"),
                )
            }
        }
        assertEquals(1, onAllNodesWithText(SecretForm.SCOPE_PROJECT).fetchSemanticsNodes().size)
        onNodeWithTag("$SECRET_MOVE_TAG-deploy").performClick()
        assertEquals(listOf("deploy"), moved)
    }

    @Test fun `the run editor's secrets text becomes a list of names`() {
        assertEquals(listOf("android-signing", "deploy"), RunCommandForm.secretsOf(" android-signing\n\ndeploy, android-signing "))
    }
}
