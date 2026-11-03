package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.plugins.official.chat.*
import com.superiorsoftllc.infantry.plugins.official.chatfeed.*

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.infantry.ui.model.AppState
import com.superiorsoftllc.infantry.ui.model.PickedModel
import com.superiorsoftllc.infantry.ui.net.Controller
import com.superiorsoftllc.infantry.ui.protocol.ProviderInfo
import com.superiorsoftllc.infantry.ui.protocol.ProviderModels
import com.superiorsoftllc.infantry.ui.protocol.Role
import com.superiorsoftllc.infantry.ui.protocol.RoleBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * UX slice 1: the New agent modal. Role grid first, then harness / provider / model (each searchable), then Add.
 * Rendered for real; the state is the daemon's frames (roles, bindings, providers, models) held in an [AppState].
 */
@OptIn(ExperimentalTestApi::class)
class NewAgentDialogUiTest {
    private val state = AppState(
        roles = listOf(Role("backend", "Backend Developer", "#112233"), Role("qa", "QA Engineer", "#445566")),
        roleBindings = mapOf("backend" to RoleBinding("backend", provider = "opencode", model = "deepseek/deepseek-chat")),
        providers = listOf(ProviderInfo("opencode", default = true), ProviderInfo("claude")),
        modelCatalog = listOf(
            ProviderModels("opencode", "deepseek", "opencode / deepseek", listOf("deepseek/deepseek-chat", "deepseek/deepseek-reasoner")),
            ProviderModels("opencode", "anthropic", "opencode / anthropic", listOf("anthropic/claude-sonnet-4")),
            ProviderModels("claude", "anthropic", "claude / anthropic", listOf("opus", "sonnet")),
        ),
    )

    @Test
    fun `pick a role, then harness and a searched model, type the task, Add hands over role executor and task`() = runDesktopComposeUiTest(1200, 900) {
        var got: Triple<String, PickedModel?, String>? = null
        setContent { NewAgentDialog(state, { r, p, t, _, _ -> got = Triple(r, p, t); null }, {}) }

        // Nothing chosen: Add is disabled and says why; the executor step is closed.
        onNodeWithTag(NEW_AGENT_ADD_TAG).assertIsNotEnabled()
        onNodeWithTag(NEW_AGENT_ADD_HINT_TAG).assertExists()
        onNodeWithTag(NEW_AGENT_HARNESS_TAG_PREFIX + "opencode").assertDoesNotExist()

        // Role grid: search narrows it, the card selects.
        onNodeWithTag(NEW_AGENT_ROLE_SEARCH_TAG).performTextInput("qa")
        onNodeWithTag(NEW_AGENT_ROLE_TAG_PREFIX + "backend").assertDoesNotExist()
        onNodeWithTag(NEW_AGENT_ROLE_TAG_PREFIX + "qa").performClick()

        // The executor step opens; a different harness, then a model found by typing.
        onNodeWithTag(NEW_AGENT_HARNESS_TAG_PREFIX + "claude").performClick()
        onNodeWithTag(NEW_AGENT_MODEL_SEARCH_TAG).performTextInput("son")
        onNodeWithTag(NEW_AGENT_MODEL_TAG_PREFIX + "opus").assertDoesNotExist()
        onNodeWithTag(NEW_AGENT_MODEL_TAG_PREFIX + "sonnet").performClick()

        // Still no task: Add stays disabled.
        onNodeWithTag(NEW_AGENT_ADD_TAG).assertIsNotEnabled()
        onNodeWithTag(NEW_AGENT_TASK_TAG).performTextInput("write the tests")
        onNodeWithTag(NEW_AGENT_ADD_TAG).assertIsEnabled()
        onNodeWithTag(NEW_AGENT_ADD_TAG).performClick()

        assertEquals(Triple("qa", PickedModel("claude", "sonnet"), "write the tests"), got)
    }

    @Test
    fun `the context limit is off by default, names the harness support, and when on Add hands over the typed limit`() = runDesktopComposeUiTest(1200, 900) {
        val limited = state.copy(providers = listOf(
            ProviderInfo("opencode", default = true, contextLimit = "unsupported", contextLimitHow = "not mapped yet"),
            ProviderInfo("claude", contextLimit = "supported", contextLimitHow = "auto-compacts at the limit"),
        ))
        var got: Int? = -1
        setContent { NewAgentDialog(limited, { _, _, _, limit, _ -> got = limit; null }, {}) }
        onNodeWithTag(NEW_AGENT_ROLE_TAG_PREFIX + "qa").performClick()
        onNodeWithTag(NEW_AGENT_HARNESS_TAG_PREFIX + "claude").performClick()
        onNodeWithTag(NEW_AGENT_MODEL_TAG_PREFIX + "opus").performClick()
        onNodeWithTag(NEW_AGENT_CONTEXT_LIMIT_NOTE_TAG).assertTextContains("claude applies the limit", substring = true)
        onNodeWithTag(NEW_AGENT_CONTEXT_LIMIT_TAG).assertDoesNotExist()

        // On with nothing typed: Add says what is missing; a size unblocks it.
        onNodeWithTag(NEW_AGENT_CONTEXT_LIMIT_SWITCH_TAG).performClick()
        onNodeWithTag(NEW_AGENT_TASK_TAG).performTextInput("go")
        onNodeWithTag(NEW_AGENT_ADD_TAG).assertIsNotEnabled()
        onNodeWithTag(NEW_AGENT_ADD_HINT_TAG).assertTextContains("Context limit", substring = true)
        onNodeWithTag(NEW_AGENT_CONTEXT_LIMIT_TAG).performTextInput("200k")
        onNodeWithTag(NEW_AGENT_ADD_TAG).performClick()
        assertEquals(200_000, got)
    }

    @Test
    fun `with the context limit off Add hands over no limit`() = runDesktopComposeUiTest(1200, 900) {
        var got: Int? = -1
        setContent { NewAgentDialog(state, { _, _, _, limit, _ -> got = limit; null }, {}) }
        onNodeWithTag(NEW_AGENT_ROLE_TAG_PREFIX + "backend").performClick()
        onNodeWithTag(NEW_AGENT_TASK_TAG).performTextInput("go")
        onNodeWithTag(NEW_AGENT_ADD_TAG).performClick()
        assertEquals(null, got)
    }

    @Test
    fun `a role with a binding seeds the executor, and no catalog yet is said on screen, not left empty`() = runDesktopComposeUiTest(1200, 900) {
        var got: Triple<String, PickedModel?, String>? = null
        setContent { NewAgentDialog(state, { r, p, t, _, _ -> got = Triple(r, p, t); null }, {}) }
        onNodeWithTag(NEW_AGENT_ROLE_TAG_PREFIX + "backend").performClick()
        onNodeWithTag(NEW_AGENT_TASK_TAG).performTextInput("go")
        onNodeWithTag(NEW_AGENT_ADD_TAG).performClick()
        assertEquals(Triple("backend", PickedModel("opencode", "deepseek/deepseek-chat"), "go"), got)

        // Empty role search result and the loading state.
        runDesktopComposeUiTest(1200, 900) {
            setContent { NewAgentDialog(AppState(), { _, _, _, _, _ -> null }, {}) }
            onNodeWithTag(NEW_AGENT_EMPTY_ROLES_TAG).assertExists()
            onNodeWithText("Loading roles...", substring = true).assertExists()
        }
    }

    /** BUG bd91ee6f: the claude catalog was fine, but the typed-for rows landed below the dialog's fold, so the search looked empty. */
    @Test
    fun `claude models matching the typed search are scrolled into view, not left below the fold`() = runDesktopComposeUiTest(1200, 560) {
        val claude = AppState(
            roles = state.roles, roleBindings = emptyMap(),
            providers = listOf(ProviderInfo("claude", models = listOf("opus", "sonnet", "haiku")), ProviderInfo("pi")),
            modelCatalog = listOf(
                ProviderModels("claude", "anthropic", "Anthropic", listOf("opus", "sonnet", "haiku", "claude-opus-5-5", "claude-sonnet-5", "claude-sonnet-5-5")),
                ProviderModels("pi", "anthropic", "anthropic", listOf("anthropic/claude-haiku-5-5")),
            ),
        )
        setContent { NewAgentDialog(claude, { _, _, _, _, _ -> null }, {}) }
        onNodeWithTag(NEW_AGENT_ROLE_TAG_PREFIX + "qa").performClick()
        onNodeWithTag(NEW_AGENT_HARNESS_TAG_PREFIX + "claude").performClick()
        onNodeWithTag(NEW_AGENT_MODEL_SEARCH_TAG).performTextInput("haiku")
        onNodeWithTag(NEW_AGENT_MODEL_TAG_PREFIX + "haiku").assertIsDisplayed()
        onNodeWithTag(NEW_AGENT_MODEL_TAG_PREFIX + "haiku").performClick()
        onNodeWithTag(NEW_AGENT_TASK_TAG).performTextInput("go")
        onNodeWithTag(NEW_AGENT_ADD_TAG).assertIsEnabled()
    }

    /** Ticket 90580b06: many roles and a tall executor step push the required task field below the fold. */
    private val crowded = state.copy(roles = (1..12).map { Role("role$it", "Role number $it", "#334455") }, roleBindings = emptyMap())

    private fun ComposeUiTest.pickRoleAndModel() {
        onNodeWithTag(NEW_AGENT_ROLE_TAG_PREFIX + "role1").performClick()
        onNodeWithTag(NEW_AGENT_HARNESS_TAG_PREFIX + "claude").performScrollTo().performClick()
        onNodeWithTag(NEW_AGENT_MODEL_SEARCH_TAG).performScrollTo().performTextInput("son")
        onNodeWithTag(NEW_AGENT_MODEL_TAG_PREFIX + "sonnet").performScrollTo().performClick()
        onNodeWithTag(NEW_AGENT_ADD_HINT_TAG).assertTextContains("first task", substring = true)
        // Back to the top, as a person who just picked lands: the task field is out of sight again.
        onNodeWithTag(NEW_AGENT_ROLE_SEARCH_TAG).performScrollTo()
    }

    @Test
    fun `at 1280x800 the clipped edge shows a scroll cue and the Add hint jumps to the missing task field`() = runDesktopComposeUiTest(1280, 800) {
        setContent { NewAgentDialog(crowded, { _, _, _, _, _ -> null }, {}) }
        // Required steps say so in words, not by a symbol alone.
        onNodeWithTag(NEW_AGENT_REQUIRED_TAG_PREFIX + "ROLE").assertExists()
        onNodeWithTag(NEW_AGENT_REQUIRED_TAG_PREFIX + "TASK").assertExists()
        pickRoleAndModel()

        // The content runs past the bottom edge: the fade marks it, and the task field is out of sight.
        onNodeWithTag(SCROLL_CUE_END_TAG).assertExists()
        onNodeWithTag(NEW_AGENT_TASK_TAG).assertIsNotDisplayed()
        onNodeWithTag(NEW_AGENT_ADD_TAG).assertIsNotEnabled()
        onNodeWithTag(NEW_AGENT_ADD_HINT_TAG).assertTextContains("first task", substring = true)

        // The hint names the missing field and, clicked, scrolls to it and focuses it.
        onNodeWithTag(NEW_AGENT_ADD_HINT_TAG).performClick()
        waitForIdle()
        onNodeWithTag(NEW_AGENT_TASK_TAG).assertIsDisplayed().assertIsFocused()
        onNodeWithTag(NEW_AGENT_TASK_TAG).performTextInput("go")
        onNodeWithTag(NEW_AGENT_ADD_HINT_TAG).assertDoesNotExist()
        onNodeWithTag(NEW_AGENT_ADD_TAG).assertIsEnabled()
    }

    @Test
    fun `on a phone-width window the hint still reaches the task field`() = runDesktopComposeUiTest(390, 740) {
        setContent { NewAgentDialog(crowded, { _, _, _, _, _ -> null }, {}) }
        pickRoleAndModel()
        onNodeWithTag(SCROLL_CUE_END_TAG).assertExists()
        onNodeWithTag(NEW_AGENT_ADD_HINT_TAG).assertIsDisplayed().performClick()
        waitForIdle()
        onNodeWithTag(NEW_AGENT_TASK_TAG).assertIsDisplayed()
        onNodeWithTag(NEW_AGENT_ADD_TAG).assertIsDisplayed()
    }

    @Test
    fun `the role grid shows whole rows only and says when more roles sit below`() = runDesktopComposeUiTest(1280, 800) {
        setContent { NewAgentDialog(crowded, { _, _, _, _, _ -> null }, {}) }
        // Three whole 48dp rows and two 6dp gaps: no row cut in half.
        onNodeWithTag(NEW_AGENT_ROLE_GRID_TAG).assertHeightIsEqualTo(156.dp)
        onNodeWithTag(NEW_AGENT_MORE_ROLES_TAG).assertExists()
        // A search that leaves one row: the grid shrinks to it and the line goes.
        onNodeWithTag(NEW_AGENT_ROLE_SEARCH_TAG).performTextInput("role12")
        onNodeWithTag(NEW_AGENT_ROLE_GRID_TAG).assertHeightIsEqualTo(48.dp)
        onNodeWithTag(NEW_AGENT_MORE_ROLES_TAG).assertDoesNotExist()
    }

    @Test
    fun `a refused spawn shows its reason inside the modal and the modal stays open`() = runDesktopComposeUiTest(1200, 900) {
        setContent { NewAgentDialog(state, { _, _, _, _, _ -> "provider claude is not installed" }, {}) }
        onNodeWithTag(NEW_AGENT_ROLE_TAG_PREFIX + "backend").performClick()
        onNodeWithTag(NEW_AGENT_ERROR_TAG).assertDoesNotExist()
        onNodeWithTag(NEW_AGENT_TASK_TAG).performTextInput("go")
        onNodeWithTag(NEW_AGENT_ADD_TAG).performClick()
        onNodeWithTag(NEW_AGENT_ERROR_TAG).assertExists()
        onNodeWithText("provider claude is not installed", substring = true).assertExists()
        onNodeWithTag(NEW_AGENT_DIALOG_TAG).assertExists()
    }

    @Test
    fun `the sidebar New agent button opens the modal`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            runDesktopComposeUiTest(1700, 1000) {
                setContent { App(Controller(scope), "ws://127.0.0.1:1/ws", null) }
                onNodeWithTag(NEW_AGENT_DIALOG_TAG).assertDoesNotExist()
                onNodeWithTag(SIDEBAR_NEW_AGENT_TAG).performClick()
                onNodeWithTag(NEW_AGENT_DIALOG_TAG).assertExists()
                onNodeWithText("Cancel").performClick()
                onNodeWithTag(NEW_AGENT_DIALOG_TAG).assertDoesNotExist()
            }
        } finally { scope.cancel() }
    }
}
