package com.superiorsoftllc.agenthq.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.agenthq.ui.model.AppState
import com.superiorsoftllc.agenthq.ui.model.BranchChangesView
import com.superiorsoftllc.agenthq.ui.model.branchChangesView
import com.superiorsoftllc.agenthq.ui.model.DiffBody
import com.superiorsoftllc.agenthq.ui.model.DiffLineKind
import com.superiorsoftllc.agenthq.ui.model.DiffRow
import com.superiorsoftllc.agenthq.ui.model.DiffTarget
import com.superiorsoftllc.agenthq.ui.model.PatchView
import com.superiorsoftllc.agenthq.ui.model.diffBody
import com.superiorsoftllc.agenthq.ui.model.diffTargets
import com.superiorsoftllc.agenthq.ui.model.label
import com.superiorsoftllc.agenthq.ui.model.projectChangesView
import com.superiorsoftllc.agenthq.ui.model.projectPatchView
import com.superiorsoftllc.agenthq.ui.model.worktreeChangesView
import com.superiorsoftllc.agenthq.ui.model.worktreeStatusKey
import com.superiorsoftllc.agenthq.ui.model.step
import com.superiorsoftllc.agenthq.ui.net.Controller

const val DIFF_MODAL_TAG = "diff-modal"
const val DIFF_MODAL_CLOSE_TAG = "diff-modal-close"
const val DIFF_MODAL_PREV_TAG = "diff-modal-prev"
const val DIFF_MODAL_NEXT_TAG = "diff-modal-next"
const val DIFF_MODAL_COUNTS_TAG = "diff-modal-counts"
const val DIFF_LINE_ADD_TAG = "diff-line-add"
const val DIFF_LINE_DEL_TAG = "diff-line-del"
const val DIFF_LINE_HUNK_TAG = "diff-line-hunk"

/**
 * DIFF-MODAL: one file's unified diff as a near-full-screen in-window overlay (the [BoardModal] pattern, so
 * commonMain stays free of platform Dialogs). Gutters carry the old and new line numbers; long lines scroll
 * sideways inside the body, never the page. Esc, ✕ or a tap on the scrim close it; ‹ › step through the
 * caller's file list (a null callback disables that end).
 *
 * Reusable: it only takes a [PatchView], so any file list (Changes panel, a branch's files) can open it.
 */
@Composable
fun DiffModal(
    patch: PatchView,
    path: String,
    onClose: () -> Unit,
    onPrev: (() -> Unit)?,
    onNext: (() -> Unit)?,
    status: String = "",
) {
    val focus = remember { FocusRequester() }
    val body = diffBody(patch)
    Box(Modifier.fillMaxSize().background(Palette.Bg.copy(alpha = 0.72f)).pointerInput(onClose) { detectTapGestures { onClose() } }) {
        Box(Modifier.fillMaxSize().padding(24.dp)) {
            Column(
                Modifier.fillMaxSize()
                    .clip(RoundedCornerShape(10.dp)).background(Palette.Bg)
                    .border(1.dp, Palette.Border, RoundedCornerShape(10.dp))
                    .testTag(DIFF_MODAL_TAG)
                    .focusRequester(focus)
                    .onPreviewKeyEvent { e ->
                        if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) { onClose(); true } else false
                    }
                    .focusable()
                    .pointerInput(Unit) { detectTapGestures { } },
            ) {
                Row(Modifier.fillMaxWidth().background(Palette.Panel).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(path, color = Palette.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, fontFamily = Mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (status.isNotBlank()) Text(status, color = Palette.Muted, fontSize = 11.sp)
                    }
                    if (body is DiffBody.Lines) {
                        Row(Modifier.testTag(DIFF_MODAL_COUNTS_TAG).semantics(mergeDescendants = true) {}.padding(horizontal = 10.dp)) {
                            Text("+${body.added}", color = Palette.Ok, fontFamily = Mono, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(6.dp))
                            Text("−${body.removed}", color = Palette.Danger, fontFamily = Mono, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    HeaderButton("‹", "Previous file", DIFF_MODAL_PREV_TAG, onPrev)
                    HeaderButton("›", "Next file", DIFF_MODAL_NEXT_TAG, onNext)
                    HeaderButton("✕", "Close the diff", DIFF_MODAL_CLOSE_TAG, onClose)
                }
                HorizontalDivider(color = Palette.Border)
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (body) {
                        DiffBody.Loading -> Notice("Loading diff…")
                        is DiffBody.Unknown -> Notice("Diff unknown: ${body.reason}")
                        DiffBody.Binary -> Notice("Binary file, no text diff.")
                        is DiffBody.Lines -> Column(Modifier.fillMaxSize()) {
                            if (body.truncated) Notice("Diff truncated: the daemon cut it, the file has more changes than shown.")
                            if (body.rows.isEmpty()) Notice("Empty diff on this side.")
                            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                                // Rows are as wide as the longest line but never narrower than the body, so the
                                // add/remove bands reach the edge; only this box scrolls sideways.
                                val viewport = maxWidth
                                Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())) {
                                    Column(Modifier.widthIn(min = viewport).width(IntrinsicSize.Max).padding(vertical = 6.dp)) { body.rows.forEach { DiffLine(it) } }
                                }
                            }
                        }
                    }
                }
            }
        }
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    }
}

@Composable
private fun HeaderButton(glyph: String, description: String, tag: String, onClick: (() -> Unit)?) {
    Box(
        Modifier.size(28.dp).clip(RoundedCornerShape(6.dp))
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .semantics { contentDescription = description }
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) { Text(glyph, color = if (onClick != null) Palette.Text else Palette.Muted.copy(alpha = 0.35f), fontSize = 15.sp) }
}

@Composable
private fun Notice(text: String) = Text(text, color = Palette.Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))

/**
 * One diff row as the Changes panel draws it: the add/remove/hunk band, the gutters, the line. Shared with the chat's
 * change box (UX-WRITE-1), which passes [oldGutter] false for a created file (there is no old side to number).
 */
@Composable
internal fun DiffLine(row: DiffRow, oldGutter: Boolean = true) {
    val (bg, fg, tag) = when (row.kind) {
        DiffLineKind.ADD -> Triple(Palette.Ok.copy(alpha = 0.14f), Palette.Ok, DIFF_LINE_ADD_TAG)
        DiffLineKind.DEL -> Triple(Palette.Danger.copy(alpha = 0.14f), Palette.Danger, DIFF_LINE_DEL_TAG)
        DiffLineKind.HUNK -> Triple(Palette.Raised, Palette.Muted, DIFF_LINE_HUNK_TAG)
        DiffLineKind.META -> Triple(Color.Transparent, Palette.Muted, "diff-line-meta")
        DiffLineKind.CONTEXT -> Triple(Color.Transparent, Palette.Text, "diff-line-context")
    }
    Row(Modifier.fillMaxWidth().background(bg).testTag(tag).semantics(mergeDescendants = true) {}) {
        if (oldGutter) Gutter(row.oldNo)
        Gutter(row.newNo)
        Text(row.text.ifEmpty { " " }, color = fg, fontFamily = Mono, fontSize = 12.sp, softWrap = false, maxLines = 1, modifier = Modifier.padding(start = 8.dp, end = 16.dp))
    }
}

@Composable
private fun Gutter(n: Int?) = Text(
    n?.toString() ?: "", color = Palette.Muted, fontFamily = Mono, fontSize = 11.sp, textAlign = TextAlign.End, maxLines = 1,
    modifier = Modifier.width(44.dp).padding(end = 6.dp),
)

/**
 * The Changes panel's host for [DiffModal]: asks for [target]'s diff (again whenever the tree status changes, so
 * the open diff follows the file being edited) and steps prev/next through the panel's own list.
 * BRANCH-VIEW-2: a [DiffTarget.branch] target is that branch's diff vs its base (`project_git_branch_diff`), stepping
 * through the branch's file list instead.
 */
@Composable
fun ProjectDiffModal(state: AppState, controller: Controller, target: DiffTarget, onTarget: (DiffTarget?) -> Unit) {
    val active = state.activeProject ?: return
    val branch = target.branch
    if (branch != null) {
        val view = branchChangesView(state.projectGitBranchChanges[active.id], branch)
        val files = (view as? BranchChangesView.Files)?.files.orEmpty()
        val order = files.map { DiffTarget(it.path, false, branch) }
        LaunchedEffect(active.id, target) { controller.loadBranchDiff(active.id, branch, target.path) }
        val file = files.firstOrNull { it.path == target.path }
        val base = (view as? BranchChangesView.Files)?.base?.ifBlank { null }
        val label = listOfNotNull(file?.status?.label(), base?.let { "$branch vs $it" } ?: branch).joinToString(" · ")
        DiffModal(
            patch = projectPatchView(state.projectGitPatch[active.id], target.path, false, branch),
            path = target.path,
            status = label,
            onClose = { onTarget(null) },
            onPrev = order.step(target, -1)?.let { t -> { onTarget(t) } },
            onNext = order.step(target, 1)?.let { t -> { onTarget(t) } },
        )
        return
    }
    // UX-CHANGES-1: a target in a worktree follows that worktree's own status; otherwise the project directory's.
    val worktree = target.worktree
    val status: Any? = if (worktree != null) state.worktreeStatus[worktreeStatusKey(active.id, worktree)] else state.projectGit[active.id]
    val listed = if (worktree != null) {
        diffTargets(worktreeChangesView(state.worktreeStatus[worktreeStatusKey(active.id, worktree)]), worktree)
    } else {
        diffTargets(projectChangesView(state.projectGit[active.id]))
    }
    val order = listed.map { it.first }
    LaunchedEffect(active.id, target, status) { controller.loadProjectDiff(active.id, target.path, target.staged, worktree) }
    val file = listed.firstOrNull { it.first == target }?.second
    val label = listOfNotNull(file?.status?.label(), if (target.staged) "staged" else "not staged").joinToString(" · ")
    DiffModal(
        patch = projectPatchView(state.projectGitPatch[active.id], target.path, target.staged, worktree = worktree),
        path = target.path,
        status = label,
        onClose = { onTarget(null) },
        onPrev = order.step(target, -1)?.let { t -> { onTarget(t) } },
        onNext = order.step(target, 1)?.let { t -> { onTarget(t) } },
    )
}
