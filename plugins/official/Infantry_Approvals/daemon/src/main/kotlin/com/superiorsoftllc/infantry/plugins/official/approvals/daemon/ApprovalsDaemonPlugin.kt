package com.superiorsoftllc.infantry.plugins.official.approvals.daemon

import com.superiorsoftllc.infantry.daemon.api.ApprovalRuleMatch
import com.superiorsoftllc.infantry.daemon.api.ApprovalRulesHook
import com.superiorsoftllc.infantry.daemon.api.DaemonApi
import com.superiorsoftllc.infantry.daemon.api.DaemonPlugin
import com.superiorsoftllc.infantry.daemon.api.Frame
import com.superiorsoftllc.infantry.protocol.ApprovalRule
import com.superiorsoftllc.infantry.protocol.ApprovalRuleDelete
import com.superiorsoftllc.infantry.protocol.ApprovalRulesList
import com.superiorsoftllc.infantry.protocol.ApprovalRulesQuery
import kotlinx.serialization.json.Json

/**
 * DP-10b: Infantry_Approvals' daemon half. Owns the stored "Always allow" rules: core asks the `hooks.approvalRules` port before
 * it opens an approval prompt and hands it the allow_always answers; this plugin also answers the `approval_rules` list and
 * `approval_rule_delete` frames. Absent or disabled = no stored rules: every prompt asks and allow_always lasts the session.
 */
class ApprovalsDaemonPlugin : DaemonPlugin {
    override fun start(api: DaemonApi) {
        val wiring = ApprovalsWiring(ApprovalRules(api.storage))
        api.hooks.approvalRules(wiring.hook)
        api.frames.handle("approval_rules") { frame, _ -> wiring.list(frame) }
        api.frames.handle("approval_rule_delete") { frame, _ -> wiring.delete(frame) }
    }
}

/** The port and the two frame answers over one [ApprovalRules] (split out of the plugin class so tests need no DaemonApi). */
class ApprovalsWiring(private val rules: ApprovalRules) {
    val hook = object : ApprovalRulesHook {
        override fun match(projectId: String?, tool: String, input: String) =
            rules.match(projectId, tool, input)?.let { ApprovalRuleMatch(it.tool, it.prefix) }
        override fun remember(projectId: String?, tool: String, input: String) = rules.add(projectId, tool, input) != null
    }

    fun list(frame: Frame): Frame = answer(frame, "approval_rules") {
        val project = ApprovalRulesQuery.decode(frame.data).projectId?.trim()?.takeIf { it.isNotEmpty() }
        listFrame(frame, project, rules.list(project))
    }

    fun delete(frame: Frame): Frame = answer(frame, "approval_rule_delete") {
        val deleted = rules.delete(ApprovalRuleDelete.decode(frame.data).ruleId)
        listFrame(frame, deleted.projectId, rules.list(deleted.projectId))
    }

    private fun answer(frame: Frame, type: String, block: () -> Frame): Frame = try {
        block()
    } catch (e: IllegalArgumentException) {
        Frame("error", "$type refused: ${e.message ?: "invalid request"}", frame.sessionId, frame.requestId)
    } catch (e: Exception) {
        Frame("error", "$type failed: ${e.message ?: e::class.simpleName}", frame.sessionId, frame.requestId)
    }

    private fun listFrame(frame: Frame, projectId: String?, list: List<ApprovalRule>) = Frame(
        "approval_rules",
        json.encodeToString(ApprovalRulesList.serializer(), ApprovalRulesList(projectId, list)),
        frame.sessionId,
        frame.requestId,
    )

    private companion object {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}
