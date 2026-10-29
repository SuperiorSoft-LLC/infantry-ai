package com.superiorsoftllc.agenthq.plugins.official.roles

import com.superiorsoftllc.agenthq.ui.*

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import com.superiorsoftllc.agenthq.ui.shell.LocalShellContext
import com.superiorsoftllc.agenthq.ui.block.layout.Block
import com.superiorsoftllc.agenthq.ui.block.layout.BlockScope
import com.superiorsoftllc.agenthq.ui.block.layout.Place
import com.superiorsoftllc.agenthq.ui.block.layout.RenderPlaced

private const val ROLES = "infantry.shell.roles"
private const val FORM = "$ROLES.form"

/** The open role manager's page; null outside [RolesBlock] (a child moved elsewhere draws nothing). */
private val LocalRoleManagerNav = compositionLocalOf<RoleManagerNav?> { null }

/** The open role form's draft; null outside [RoleFormBlock] (a child moved elsewhere draws nothing). */
private val LocalRoleFormDraft = compositionLocalOf<RoleFormDraft?> { null }

/**
 * BLOCKS-SHELL-1: the role manager (Configuration > Roles), while it is open. A dialog has no node in the layer, so the
 * placement has nothing to wear.
 * BLOCKS-LAYOUT L12: a container of child blocks `infantry.shell.roles.<name>`, drawn straight into the dialog's scrolling
 * column, top to bottom. The frame (dialog, title, Done) and the page shown (list or form) stay its own.
 */
class RolesBlock(children: List<Block>, place: Place = Place()) : Block(ROLES, ROLES, place, children) {
    @Composable
    override fun Render(scope: BlockScope) {
        val roles = LocalShellContext.current?.roles ?: return
        if (!roles.managerOpen) return
        RoleManagerFrame(onDismiss = roles::closeManager) { nav ->
            CompositionLocalProvider(LocalRoleManagerNav provides nav) { RenderPlaced(children) }
        }
    }

    override fun withChildren(children: List<Block>) = RolesBlock(children, place)
}

/** The line on when changes apply. */
object RolesHint : Block("roleManagerHint", "$ROLES.hint") {
    @Composable
    override fun Render(scope: BlockScope) {
        if (LocalRoleManagerNav.current == null) return
        RoleManagerHint(scope.modifier)
    }
}

/** The list page: New role and a row per role (nothing while a role is open); its placement goes on New role. */
object RolesList : Block("roleList", "$ROLES.list") {
    @Composable
    override fun Render(scope: BlockScope) {
        val ctx = LocalShellContext.current ?: return
        val nav = LocalRoleManagerNav.current ?: return
        RoleManagerList(ctx.roles.roles, ctx.roles.bindings, ctx.roles.traits, ctx.link.canWrite, nav, scope.modifier)
    }
}

/**
 * The form page while a role is open (nothing on the list): a container of child blocks `infantry.shell.roles.form.<name>`,
 * drawn straight into the dialog's column. The fields, the notes load, save and delete stay its own (the form's frame).
 */
class RoleFormBlock(children: List<Block>, place: Place = Place()) : Block("roleForm", FORM, place, children) {
    @Composable
    override fun Render(scope: BlockScope) {
        val ctx = LocalShellContext.current ?: return
        val nav = LocalRoleManagerNav.current ?: return
        RoleManagerForm(remember(ctx) { RolesDeps(ctx) }, nav) { draft ->
            CompositionLocalProvider(LocalRoleFormDraft provides draft) { with(nav.column) { RenderPlaced(children) } }
        }
    }

    override fun withChildren(children: List<Block>) = RoleFormBlock(children, place)
}

/** The form's title (and the older-daemon notice); its placement goes on the title. */
object RoleFormTitleBlock : Block("roleFormTitle", "$FORM.title") {
    @Composable
    override fun Render(scope: BlockScope) {
        RoleFormTitle(LocalRoleFormDraft.current ?: return, scope.modifier)
    }
}

/** Id (a new role only), name, description, role notes, colour and icon; its placement goes on the name field. */
object RoleFormIdentityBlock : Block("roleFormIdentity", "$FORM.identity") {
    @Composable
    override fun Render(scope: BlockScope) {
        RoleFormIdentity(LocalRoleFormDraft.current ?: return, scope.modifier)
    }
}

/** Default provider, model and effort; its placement goes on the heading. */
object RoleFormExecutorBlock : Block("roleFormExecutor", "$FORM.executor") {
    @Composable
    override fun Render(scope: BlockScope) {
        RoleFormExecutor(LocalRoleFormDraft.current ?: return, scope.modifier)
    }
}

/** The role's traits; its placement goes on the heading. */
object RoleFormTraitsBlock : Block("roleFormTraits", "$FORM.traits") {
    @Composable
    override fun Render(scope: BlockScope) {
        RoleFormTraits(LocalRoleFormDraft.current ?: return, scope.modifier)
    }
}

/** The active project's notes for the role, read-only (nothing for a new role); its placement goes on the heading. */
object RoleFormNotesBlock : Block("roleFormNotes", "$FORM.notes") {
    @Composable
    override fun Render(scope: BlockScope) {
        RoleFormNotes(LocalRoleFormDraft.current ?: return, scope.modifier)
    }
}

/** The last refusal, Delete, Back and Save, and Delete's confirmation; its placement goes on the button row. */
object RoleFormActionsBlock : Block("roleFormActions", "$FORM.actions") {
    @Composable
    override fun Render(scope: BlockScope) {
        RoleFormActions(LocalRoleFormDraft.current ?: return, scope.modifier)
    }
}

private val RolesForm = RoleFormBlock(
    listOf(RoleFormTitleBlock, RoleFormIdentityBlock, RoleFormExecutorBlock, RoleFormTraitsBlock, RoleFormNotesBlock, RoleFormActionsBlock),
)

val ShellRoles = RolesBlock(listOf(RolesHint, RolesList, RolesForm))
