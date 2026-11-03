package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.plugin.host.PluginFolders
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.util.UUID

/**
 * NS4 (docs/NORTH-STAR-DESIGN.md 2c): the app manifest, `app.json` beside a shell app's `plugins` folder.
 *
 * `appId` is fixed once written: it names the app's state dir (`~/.infantry/apps/<appId>`) and its MSI upgrade
 * identity ([upgradeUuid]), so changing it orphans the user's data and installs a second product.
 */
data class AppManifest(
    val appId: String,
    val name: String,
    val icon: String? = null,
    val windowTitle: String = name,
    val daemon: Daemon = Daemon(),
) {
    data class Daemon(val mode: String = MODE_CONNECT_OR_BUNDLE, val url: String? = null, val seedProject: String? = null)

    /** The per-app state dir; never the Infantry install's. NS5 boots the bundled daemon on it. */
    fun stateDir(home: String = System.getProperty("user.home")): File = File(home, ".infantry/apps/$appId")

    /** The MSI upgrade code: derived from [appId] only, so one appId upgrades over itself and never over Infantry AI. */
    val upgradeUuid: String get() = upgradeUuid(appId)

    companion object {
        const val FILE = "app.json"
        const val MODE_CONNECT_OR_BUNDLE = "connect-or-bundle"
        val MODES = setOf(MODE_CONNECT_OR_BUNDLE, "connect", "bundle")

        /** A reverse-DNS-ish id that is also a safe folder name. */
        private val APP_ID = Regex("""[A-Za-z0-9][A-Za-z0-9._-]{0,127}""")

        /** Name-based (v3) UUID of `infantry.app:<appId>`; packageShellMsi computes the same in build.gradle.kts. */
        fun upgradeUuid(appId: String): String = UUID.nameUUIDFromBytes("infantry.app:$appId".toByteArray(Charsets.UTF_8)).toString()

        fun parse(text: String): AppManifest {
            val o = Json.parseToJsonElement(text).jsonObject
            fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
            val appId = requireNotNull(o.str("appId")) { "app.json: appId is required" }
            require(APP_ID.matches(appId) && ".." !in appId) { "app.json: appId '$appId' must be letters, digits, '.', '_' or '-'" }
            val name = o.str("name") ?: appId
            val d = o["daemon"] as? JsonObject
            val mode = d?.str("mode") ?: MODE_CONNECT_OR_BUNDLE
            require(mode in MODES) { "app.json: daemon.mode '$mode' is not one of $MODES" }
            return AppManifest(appId, name, o.str("icon"), o.str("windowTitle") ?: name, Daemon(mode, d?.str("url"), d?.str("seedProject")))
        }

        fun read(appDir: File): AppManifest = parse(File(appDir, FILE).readText())
    }
}

/**
 * NS4: `--app <appDir>` (or `-Dinfantry.app.dir=<appDir>`, what the shell installer's launcher passes). Reads
 * `<appDir>/app.json` and points the plugins root at `<appDir>/plugins` through the existing
 * [PluginFolders.ROOT_PROPERTY] (an INFANTRY_PLUGINS_ROOT set by the operator still wins, as everywhere).
 */
object ShellApp {
    const val ARG = "--app"
    const val DIR_PROPERTY = "infantry.app.dir"

    /** The app this JVM runs, once [apply] found one; null for Infantry AI itself. */
    @Volatile var current: ShellAppLaunch? = null
        private set

    /** The app dir named by [args] or [DIR_PROPERTY], or null. */
    fun appDir(args: Array<String>, getProperty: (String) -> String? = System::getProperty): File? {
        val i = args.indexOf(ARG)
        val fromArgs = if (i >= 0) requireNotNull(args.getOrNull(i + 1)?.takeIf { it.isNotBlank() }) { "$ARG needs a directory" } else null
        return (fromArgs ?: getProperty(DIR_PROPERTY)?.takeIf { it.isNotBlank() })?.let { File(it).absoluteFile }
    }

    /**
     * Pure: what an app dir means for this launch. Dev/runShell: `<appDir>/plugins`. [installed] (the shell MSI's
     * launcher): `<stateDir>/plugins`, because the install dir is deleted by every upgrade and uninstall (INSTALL-1).
     */
    fun resolve(appDir: File, home: String = System.getProperty("user.home"), installed: Boolean = false): ShellAppLaunch {
        val manifest = AppManifest.read(appDir)
        val stateDir = manifest.stateDir(home)
        return ShellAppLaunch(appDir, manifest, if (installed) File(stateDir, "plugins") else File(appDir, "plugins"), stateDir)
    }

    /** Resolves [args] and sets the plugins root; null (and nothing set) without `--app`. */
    fun apply(args: Array<String>): ShellAppLaunch? {
        val dir = appDir(args) ?: return null
        val launch = resolve(dir, installed = LaunchProfile.isInstalled())
        launch.pluginsRoot.mkdirs()
        System.setProperty(PluginFolders.ROOT_PROPERTY, launch.pluginsRoot.path)
        System.setProperty(DIR_PROPERTY, dir.path)
        current = launch
        println("[infantry-app] app=${launch.manifest.appId} dir=$dir plugins=${launch.pluginsRoot} state=${launch.stateDir}")
        return launch
    }
}

data class ShellAppLaunch(val appDir: File, val manifest: AppManifest, val pluginsRoot: File, val stateDir: File)
