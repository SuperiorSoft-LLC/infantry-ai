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

    @Test fun `the run editor's secrets text becomes a list of names`() {
        assertEquals(listOf("android-signing", "deploy"), RunCommandForm.secretsOf(" android-signing\n\ndeploy, android-signing "))
    }
}
