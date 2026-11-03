package com.superiorsoftllc.infantry.ui.shell.blocks

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.ScrollState
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.infantry.ui.Button
import com.superiorsoftllc.infantry.ui.Checkbox
import com.superiorsoftllc.infantry.ui.LocalShell
import com.superiorsoftllc.infantry.ui.Palette
import com.superiorsoftllc.infantry.ui.TextButton
import com.superiorsoftllc.infantry.ui.block.layout.Block
import com.superiorsoftllc.infantry.ui.block.layout.BlockScope
import com.superiorsoftllc.infantry.ui.shell.DropCheck
import com.superiorsoftllc.infantry.ui.shell.DropPreview
import com.superiorsoftllc.infantry.ui.shell.LocalSoftRestart
import com.superiorsoftllc.infantry.ui.shell.NodeAsk
import com.superiorsoftllc.infantry.ui.shell.PluginDropIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val DROP_CONSENT_NODE = "infantry.shell.dropConsent"
const val SAFE_MODE_NODE = "infantry.shell.safeMode"

const val DROP_BOX_TAG = "shell-drop-box"
const val DROP_BOX_CHOOSE_TAG = "shell-drop-box-choose"
const val DROP_BOX_LOCKED_TAG = "shell-drop-box-locked"
const val DROP_BOX_ERROR_TAG = "shell-drop-box-error"
const val DROP_BOX_CHECKING_TAG = "shell-drop-box-checking"
const val DROP_CONSENT_INSTALLING_TAG = "shell-drop-consent-installing"
const val DROP_CONSENT_TAG = "shell-drop-consent"
const val DROP_CONSENT_INSTALL_TAG = "shell-drop-consent-install"
const val DROP_CONSENT_CANCEL_TAG = "shell-drop-consent-cancel"
const val DROP_CONSENT_ERROR_TAG = "shell-drop-consent-error"
const val DROP_CONSENT_SIGNER_TAG = "shell-drop-consent-signer"
const val DROP_CONSENT_TIER_TAG = "shell-drop-consent-tier"
const val DROP_CONSENT_LIST_TAG = "shell-drop-consent-list"
const val DROP_CONSENT_NODE_TAG_PREFIX = "shell-drop-consent-node-"
const val DROP_CONSENT_TICK_TAG_PREFIX = "shell-drop-consent-tick-"
const val SAFE_MODE_BANNER_TAG = "shell-safe-mode"
const val SAFE_MODE_REMOVE_TAG_PREFIX = "shell-safe-mode-remove-"

/**
 * S6 (drop-in path): what the empty centre slot draws until a module fills it (`PlaceholderBlock.empty`): drop a `.ihq`
 * here, or choose one. A file that passes its checks opens the consent dialog ([ShellDropConsent]); nothing is
 * installed before that. Under the demo lock it is disabled and says why. Draws nothing where the host cannot load
 * modules (web, Android: [com.superiorsoftllc.infantry.ui.shell.PlatformHost.dropIn] is null).
 */
object ShellDropBox : Block("dropBox", "infantry.shell.dropBox") {
    @Composable
    override fun Render(scope: BlockScope) {
        val s = LocalShell.current
        val dropIn = s.host.dropIn ?: return
        DropBox(dropIn, scope.modifier) { s.ui.dropPreview = it }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DropBox(dropIn: PluginDropIn, modifier: Modifier, onReady: (DropPreview) -> Unit) {
    val enabled = !dropIn.lockdown
    var error by remember { mutableStateOf<String?>(null) }
    var hovering by remember { mutableStateOf(false) }
    // S7: the signature check reads the whole package; it runs off the UI thread, and the box says it is checking.
    var checking by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val take: (String) -> Unit = { path ->
        if (checking == null) {
            checking = path.substringAfterLast('/').substringAfterLast('\\')
            error = null
            scope.launch {
                val check = withContext(Dispatchers.Default) {
                    runCatching { dropIn.inspect(path) }.getOrElse { DropCheck.Refused("could not check the file: ${it.message}") }
                }
                checking = null
                when (check) {
                    is DropCheck.Ready -> onReady(check.preview)
                    is DropCheck.Refused -> error = check.message
                }
            }
        }
    }
    val target = remember(dropIn, enabled) {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) { hovering = true }
            override fun onExited(event: DragAndDropEvent) { hovering = false }
            override fun onEnded(event: DragAndDropEvent) { hovering = false }
            override fun onDrop(event: DragAndDropEvent): Boolean {
                hovering = false
                val path = dropIn.droppedPaths(event).firstOrNull()
                if (!enabled || path == null || checking != null) return false
                take(path)
                return true
            }
        }
    }
    val shape = RoundedCornerShape(10.dp)
    Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 460.dp).clip(shape)
                .border(1.dp, if (hovering && enabled) Palette.Accent else Palette.Border, shape)
                .dragAndDropTarget(shouldStartDragAndDrop = { enabled }, target = target)
                .testTag(DROP_BOX_TAG)
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Drop a plugin here", color = if (enabled) Palette.Text else Palette.Muted, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            if (enabled) {
                Text(
                    "A .ihq file. Before anything is installed you see who signed it and every part of the window it asks for.",
                    color = Palette.Muted, fontSize = 13.sp, textAlign = TextAlign.Center,
                )
            } else {
                Text(
                    "The lockdown is on: plugins cannot be installed here.",
                    color = Palette.Muted, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.testTag(DROP_BOX_LOCKED_TAG),
                )
            }
            Button(
                onClick = { dropIn.pickPackage()?.let(take) },
                enabled = enabled && dropIn.canPick && checking == null,
                modifier = Modifier.testTag(DROP_BOX_CHOOSE_TAG),
            ) { Text("Choose file") }
            checking?.let { Text("Checking $it ...", color = Palette.Muted, fontSize = 12.sp, modifier = Modifier.testTag(DROP_BOX_CHECKING_TAG)) }
            error?.let { Text(it, color = Palette.Danger, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.testTag(DROP_BOX_ERROR_TAG)) }
        }
    }
}

/**
 * S6: the consent dialog (locked: no module replaces, wraps or injects into it). The checked file's name, version,
 * author, signer and the tier it will get, and every shell node it asks for. A core (Infantry-signed) module gets every
 * unlocked node it asks for; a community one gets only the ones left ticked, written to modules.json as its
 * `allowOverride` / `allowInject`. Install installs, then soft-restarts the window; Cancel installs nothing.
 */
object ShellDropConsent : Block(DROP_CONSENT_NODE, DROP_CONSENT_NODE) {
    @Composable
    override fun Render(scope: BlockScope) {
        val s = LocalShell.current
        val dropIn = s.host.dropIn ?: return
        val preview = s.ui.dropPreview ?: return
        val restart = LocalSoftRestart.current
        ConsentDialog(dropIn, preview, onClose = { s.ui.dropPreview = null }, onInstalled = { s.ui.dropPreview = null; restart?.invoke() })
    }
}

@Composable
private fun ConsentDialog(dropIn: PluginDropIn, preview: DropPreview, onClose: () -> Unit, onInstalled: () -> Unit) {
    var granted by remember(preview) { mutableStateOf(preview.asks.filter { it.grantable(preview.core) }.map { it.node }.toSet()) }
    var error by remember(preview) { mutableStateOf<String?>(null) }
    var installing by remember(preview) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onClose,
        modifier = Modifier.testTag(DROP_CONSENT_TAG),
        title = { Text("Install ${preview.name}?") },
        text = {
            val scroll = rememberScrollState()
            Column(Modifier.heightIn(max = 420.dp).scrollThumb(scroll).verticalScroll(scroll).padding(end = 10.dp).testTag(DROP_CONSENT_LIST_TAG), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Version ${preview.version}" + (preview.author?.let { " · by $it" } ?: ""), fontSize = 13.sp)
                Text("Signed by: ${preview.signer ?: "nobody (unsigned)"}", fontSize = 13.sp, modifier = Modifier.testTag(DROP_CONSENT_SIGNER_TAG))
                Text(
                    // S7: worded from the signer's name; any key in trusted-keys makes a core module, not only the Infantry key.
                    if (preview.core) "Tier: core. Signed by ${preview.signerName ?: "a trusted signer"}, whose key you trust: full trust, it may fill the window's empty slots."
                    else "Tier: community. It runs inside the app with the app's full access, and gets only the parts of the window you tick below.",
                    fontSize = 13.sp, modifier = Modifier.testTag(DROP_CONSENT_TIER_TAG),
                )
                if (preview.replacesInterface) Text("This file replaces the whole interface.", color = Palette.Danger, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                if (preview.asks.isEmpty()) Text("It asks for no part of the window.", color = Palette.Muted, fontSize = 12.sp)
                // The list has a scroll thumb (scrollThumb) while there is more to read; the count says how many there are.
                else Text("Parts of the window it asks for (${preview.asks.size})" + (if (preview.asks.size > 8) ", scroll for all:" else ":"), color = Palette.Muted, fontSize = 12.sp)
                for (ask in preview.asks) NodeRow(ask, preview.core, ask.node in granted) { on -> granted = if (on) granted + ask.node else granted - ask.node }
                error?.let { Text(it, color = Palette.Danger, fontSize = 12.sp, modifier = Modifier.testTag(DROP_CONSENT_ERROR_TAG)) }
                if (installing) Text("Checking and installing ...", color = Palette.Muted, fontSize = 12.sp, modifier = Modifier.testTag(DROP_CONSENT_INSTALLING_TAG))
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    // S7: the install checks the package again (signature, limits); off the UI thread.
                    installing = true; error = null
                    scope.launch {
                        val e = withContext(Dispatchers.Default) { runCatching { dropIn.install(preview, granted) }.getOrElse { "install failed: ${it.message}" } }
                        installing = false
                        if (e == null) onInstalled() else error = e
                    }
                },
                enabled = !installing,
                modifier = Modifier.testTag(DROP_CONSENT_INSTALL_TAG),
            ) { Text("Install") }
        },
        dismissButton = { TextButton(onClick = onClose, modifier = Modifier.testTag(DROP_CONSENT_CANCEL_TAG)) { Text("Cancel") } },
    )
}

@Composable
private fun NodeRow(ask: NodeAsk, core: Boolean, ticked: Boolean, onTick: (Boolean) -> Unit) {
    val verb = when (ask.kind) {
        NodeAsk.Kind.OVERRIDE -> "Replace"
        NodeAsk.Kind.INJECT -> "Add into"
        NodeAsk.Kind.FILL -> "Fill"
    }
    val refusal = when {
        ask.locked -> "refused: locked"
        !core && ask.kind == NodeAsk.Kind.FILL -> "refused: only a core plugin fills a slot"
        else -> null
    }
    Row(Modifier.fillMaxWidth().testTag(DROP_CONSENT_NODE_TAG_PREFIX + ask.node), verticalAlignment = Alignment.CenterVertically) {
        if (ask.grantable(core)) Checkbox(checked = ticked, onCheckedChange = onTick, modifier = Modifier.testTag(DROP_CONSENT_TICK_TAG_PREFIX + ask.node))
        Text("$verb ${ask.node}", fontSize = 12.sp, modifier = Modifier.weight(1f).padding(start = 4.dp))
        refusal?.let { Text(it, color = Palette.Muted, fontSize = 11.sp) }
    }
}

/**
 * S6: safe mode's banner (locked), over the top of the window while the shell runs with every community module off
 * (`--safe-mode`): it says so, and lists the installed community modules with a Remove each, so a plugin that breaks
 * the window can always be taken out. Draws nothing outside safe mode.
 */
object ShellSafeMode : Block(SAFE_MODE_NODE, SAFE_MODE_NODE) {
    @Composable
    override fun Render(scope: BlockScope) {
        val dropIn = LocalShell.current.host.dropIn ?: return
        if (!dropIn.safeMode) return
        SafeModeBanner(dropIn, scope.modifier)
    }
}

@Composable
private fun SafeModeBanner(dropIn: PluginDropIn, modifier: Modifier) {
    var installed by remember { mutableStateOf(dropIn.installed()) }
    var note by remember { mutableStateOf<String?>(null) }
    val shape = RoundedCornerShape(8.dp)
    Box(modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 560.dp).clip(shape).background(Palette.Raised).border(1.dp, Palette.Danger, shape)
                .testTag(SAFE_MODE_BANNER_TAG).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Safe mode: community plugins are off", color = Palette.Danger, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Text("Start without --safe-mode to load them again. Remove one that stops the window from working:", color = Palette.Muted, fontSize = 12.sp)
            if (installed.isEmpty()) Text("No community plugin is installed.", color = Palette.Muted, fontSize = 12.sp)
            for (name in installed) Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, fontSize = 12.sp, modifier = Modifier.weight(1f))
                TextButton(
                    onClick = { note = dropIn.remove(name) ?: "$name removed."; installed = dropIn.installed() },
                    enabled = !dropIn.lockdown,
                    modifier = Modifier.testTag(SAFE_MODE_REMOVE_TAG_PREFIX + name),
                ) { Text("Remove") }
            }
            note?.let { Text(it, color = Palette.Muted, fontSize = 12.sp) }
        }
    }
}

/**
 * S7: a thumb along the right edge of a scrolling column, drawn only while there is more to scroll to. Compose's desktop
 * scrollbar is not in common code, and the consent list scrolled with no sign that it did.
 */
internal fun Modifier.scrollThumb(state: ScrollState): Modifier = drawWithContent {
    drawContent()
    val max = state.maxValue
    if (max > 0 && max != Int.MAX_VALUE) {
        val view = size.height
        val thumb = (view * view / (view + max)).coerceAtLeast(24.dp.toPx()).coerceAtMost(view)
        val top = (view - thumb) * state.value / max
        val w = 4.dp.toPx()
        drawRoundRect(Palette.Muted, Offset(size.width - w, top), Size(w, thumb), CornerRadius(w / 2))
    }
}
