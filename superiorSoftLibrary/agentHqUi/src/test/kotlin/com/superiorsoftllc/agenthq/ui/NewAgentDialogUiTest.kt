package com.superiorsoftllc.agenthq.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.agenthq.ui.model.AppState
import com.superiorsoftllc.agenthq.ui.model.PickedModel
import com.superiorsoftllc.agenthq.ui.net.Controller
import com.superiorsoftllc.agenthq.ui.protocol.ProviderInfo
import com.superiorsoftllc.agenthq.ui.protocol.ProviderModels
import com.superiorsoftllc.agenthq.ui.protocol.Role
import com.superiorsoftllc.agenthq.ui.protocol.RoleBinding
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
        setContent { NewAgentDialog(state, { r, p, t -> got = Triple(r, p, t); null }, {}) }

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
    fun `a role with a binding seeds the executor, and no catalog yet is said on screen, not left empty`() = runDesktopComposeUiTest(1200, 900) {
        var got: Triple<String, PickedModel?, String>? = null
        setContent { NewAgentDialog(state, { r, p, t -> got = Triple(r, p, t); null }, {}) }
        onNodeWithTag(NEW_AGENT_ROLE_TAG_PREFIX + "backend").performClick()
        onNodeWithTag(NEW_AGENT_TASK_TAG).performTextInput("go")
        onNodeWithTag(NEW_AGENT_ADD_TAG).performClick()
        assertEquals(Triple("backend", PickedModel("opencode", "deepseek/deepseek-chat"), "go"), got)

        // Empty role search result and the loading state.
        runDesktopComposeUiTest(1200, 900) {
            setContent { NewAgentDialog(AppState(), { _, _, _ -> null }, {}) }
            onNodeWithTag(NEW_AGENT_EMPTY_ROLES_TAG).assertExists()
            onNodeWithText("Loading roles...", substring = true).assertExists()
        }
    }

    @Test
    fun `a refused spawn shows its reason inside the modal and the modal stays open`() = runDesktopComposeUiTest(1200, 900) {
        setContent { NewAgentDialog(state, { _, _, _ -> "provider claude is not installed" }, {}) }
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
