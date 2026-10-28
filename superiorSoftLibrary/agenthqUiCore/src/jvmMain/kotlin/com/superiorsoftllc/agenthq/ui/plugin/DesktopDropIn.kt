package com.superiorsoftllc.agenthq.ui.plugin

import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.awtTransferable
import com.superiorsoftllc.agenthq.plugin.api.ModuleManifest
import com.superiorsoftllc.agenthq.plugin.api.Tier
import com.superiorsoftllc.agenthq.plugin.host.IhqInstaller
import com.superiorsoftllc.agenthq.plugin.host.IhqResult
import com.superiorsoftllc.agenthq.plugin.host.ModulesFile
import com.superiorsoftllc.agenthq.plugin.host.PluginFolders
import com.superiorsoftllc.agenthq.ui.shell.DropCheck
import com.superiorsoftllc.agenthq.ui.shell.DropPreview
import com.superiorsoftllc.agenthq.ui.shell.NodeAsk
import com.superiorsoftllc.agenthq.ui.shell.PluginDropIn
import com.superiorsoftllc.agenthq.ui.shell.blocks.CoreShell
import java.awt.datatransfer.DataFlavor
import java.io.File

/**
 * S6 (drop-in path): the desktop's [PluginDropIn], over S5's [IhqInstaller] and the plugins root [root]. The tier is the
 * installer's (from the signer), never the file's. Under the demo lock nothing is installed or removed.
 */
class DesktopDropIn(
    private val root: File,
    override val demoLock: Boolean,
    override val safeMode: Boolean,
    private val picker: ((String) -> String?)? = null,
    /** S7: unloads the running modules (stop, unsubscribe, close their jars) before a folder is deleted: Windows will not delete an open jar. */
    private val release: () -> Unit = {},
) : PluginDropIn {
    override val canPick: Boolean get() = picker != null

    override fun inspect(path: String): DropCheck {
        if (demoLock) return DropCheck.Refused(LOCKED)
        val file = File(path)
        if (!file.name.endsWith(".ihq", ignoreCase = true)) return DropCheck.Refused("${file.name} is not a .ihq plugin package")
        return when (val r = IhqInstaller.inspect(file, root)) {
            is IhqResult.Checked -> DropCheck.Ready(preview(file, r))
            is IhqResult.Refused -> DropCheck.Refused("${file.name} was refused: ${r.message}")
            is IhqResult.Installed -> DropCheck.Refused("unexpected install") // inspect never installs
        }
    }

    override fun install(preview: DropPreview, granted: Set<String>): String? {
        if (demoLock) return LOCKED
        val file = File(preview.path)
        // The file is checked again as it is installed; one that changed since the dialog opened is refused.
        val again = IhqInstaller.inspect(file, root)
        if (again !is IhqResult.Checked || preview(file, again) != preview) return "${file.name} changed since it was checked; drop it again"
        return when (val r = IhqInstaller.install(file, root)) {
            is IhqResult.Installed -> {
                if (r.tier == Tier.COMMUNITY) {
                    val asks = preview.asks.filter { it.grantable(core = false) && it.node in granted }
                    ModulesFile.grant(root, r.name, asks.filter { it.kind == NodeAsk.Kind.OVERRIDE }.map { it.node }, asks.filter { it.kind == NodeAsk.Kind.INJECT }.map { it.node })
                }
                null
            }
            is IhqResult.Refused -> "${file.name} was refused: ${r.message}"
            is IhqResult.Checked -> "unexpected check"
        }
    }

    override fun installed(): List<String> {
        val config = PluginFolders.config(root)
        return PluginFolders.scan(root, config).filter { it.tier == Tier.COMMUNITY }.map { it.folderName }.sorted()
    }

    override fun remove(name: String): String? {
        if (demoLock) return LOCKED
        if (!safeMode) release()
        return IhqInstaller.uninstall(root, name)
    }

    override fun pickPackage(): String? = picker?.invoke("Choose a plugin (.ihq)")

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    override fun droppedPaths(event: DragAndDropEvent): List<String> = runCatching {
        val t = event.awtTransferable
        if (!t.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) return@runCatching emptyList()
        (t.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>).orEmpty().filterIsInstance<File>().filter { it.isFile }.map { it.absolutePath }
    }.getOrDefault(emptyList())

    companion object {
        const val LOCKED = "The demo lock is on: plugins cannot be installed or removed."

        /** What [file]'s checked manifest asks for, as the consent dialog lists it. */
        fun preview(file: File, r: IhqResult.Checked): DropPreview = DropPreview(
            path = file.absolutePath, name = r.manifest.name, version = r.manifest.version, author = r.manifest.author,
            signer = r.signer, core = r.tier == Tier.CORE, asks = asks(r.manifest),
        )

        fun asks(m: ModuleManifest): List<NodeAsk> {
            fun ask(node: String, kind: NodeAsk.Kind) = NodeAsk(node, kind, node in CoreShell.locked)
            return (m.fills.map { ask(it, NodeAsk.Kind.FILL) } + m.shell.map { ask(it.nodeId, NodeAsk.Kind.OVERRIDE) } +
                m.inject.map { ask(it.targetId, NodeAsk.Kind.INJECT) }).distinctBy { it.kind to it.node }
        }
    }
}
