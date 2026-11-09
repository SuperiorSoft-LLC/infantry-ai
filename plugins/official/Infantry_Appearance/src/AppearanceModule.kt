// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.appearance

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import com.superiorsoftllc.infantry.plugin.api.ConfigDeclaration
import com.superiorsoftllc.infantry.plugin.api.ConfigFieldDeclaration
import com.superiorsoftllc.infantry.plugin.api.ConfigFieldTypes
import com.superiorsoftllc.infantry.plugin.api.ConfigScopes
import com.superiorsoftllc.infantry.plugin.api.FillDeclaration
import com.superiorsoftllc.infantry.plugin.api.ModuleManifest
import com.superiorsoftllc.infantry.ui.AGENT_AVATAR_PORTRAIT
import com.superiorsoftllc.infantry.ui.AGENT_AVATAR_RENDERERS
import com.superiorsoftllc.infantry.ui.AvatarStyleChoice
import com.superiorsoftllc.infantry.ui.LocalModalBlur
import com.superiorsoftllc.infantry.ui.Palette
import com.superiorsoftllc.infantry.ui.block.layout.Block
import com.superiorsoftllc.infantry.ui.block.layout.BlockScope
import com.superiorsoftllc.infantry.ui.block.layout.config
import com.superiorsoftllc.infantry.ui.model.ThemeAccent
import com.superiorsoftllc.infantry.ui.model.ThemeSettings
import com.superiorsoftllc.infantry.ui.plugin.InfantryModule
import com.superiorsoftllc.infantry.ui.plugin.ModuleSource
import com.superiorsoftllc.infantry.ui.plugin.UiPlugin
import com.superiorsoftllc.infantry.ui.plugin.UiRegistrar
import kotlinx.serialization.json.JsonPrimitive

/** The shell's theme slot, which this module fills; also the node id its config is declared on and injected from. */
const val THEME_FILL_ID = "infantry.shell.theme"

/** CONFIG-3: `accent` (a [ThemeAccent] name), `dark` and `blur`, the keys of node [THEME_FILL_ID]. */
const val ACCENT_KEY = "accent"
const val DARK_KEY = "dark"
const val BLUR_KEY = "blur"

/** AVATAR-PICKER-1: the avatar style field of node [THEME_FILL_ID]: a key registered under `agent.avatar.renderers`; "portrait" is the default. */
const val AVATAR_STYLE_KEY = "agentAvatarStyle"

/**
 * The module's config, the same as its module.json `config` (a test pins them equal): the Settings dialog's Appearance
 * tab is generated from it. Compiled-in hosts (web, Android) have no module.json to read, so it is also here.
 */
val APPEARANCE_CONFIG: List<ConfigDeclaration> = listOf(
    ConfigDeclaration(
        node = THEME_FILL_ID, group = "Theme", tab = "Appearance", order = 10, title = "Theme",
        applies = "Applies to: the whole app, at once. The accent colours buttons, focus borders and highlights.",
        keywords = listOf("color", "colour", "accent", "dark", "light"),
        fields = listOf(
            ConfigFieldDeclaration(
                ACCENT_KEY, ConfigFieldTypes.SELECT, "Accent", JsonPrimitive(ThemeAccent.RED.name), ThemeAccent.entries.map { it.name }, order = 1,
                optionLabels = ThemeAccent.entries.associate { it.name to it.label }, scope = ConfigScopes.DEVICE,
            ),
            ConfigFieldDeclaration(DARK_KEY, ConfigFieldTypes.TOGGLE, "Dark mode", JsonPrimitive(true), order = 2, scope = ConfigScopes.DEVICE),
        ),
    ),
    ConfigDeclaration(
        node = THEME_FILL_ID, group = "Blur", tab = "Appearance", order = 20, title = "Blur behind dialogs",
        applies = "Applies to: every dialog. Blurs the window behind it while it is open.",
        keywords = listOf("dialog", "scrim", "modal"),
        fields = listOf(ConfigFieldDeclaration(BLUR_KEY, ConfigFieldTypes.TOGGLE, "Blur behind dialogs", JsonPrimitive(true), scope = ConfigScopes.DEVICE)),
    ),
    ConfigDeclaration(
        node = THEME_FILL_ID, group = "Agent avatars", tab = "Appearance", order = 25, title = "Avatar style",
        applies = "Applies to: every agent avatar in the app. Changes how agents look; Portrait is the default.",
        keywords = listOf("avatar", "agent", "look", "mascot", "picture"),
        fields = listOf(
            ConfigFieldDeclaration(
                AVATAR_STYLE_KEY, ConfigFieldTypes.IMPLEMENTATION, "Avatar style", JsonPrimitive(AGENT_AVATAR_PORTRAIT), registry = AGENT_AVATAR_RENDERERS, exposed = true,
                description = "How agents look in lists, chat and the title bar. A style that is removed shows as Portrait.", scope = ConfigScopes.DEVICE,
            ),
        ),
    ),
    ConfigDeclaration(node = "infantry.shell.config.userPortrait", group = "Your portrait", tab = "Appearance", order = 30, title = "Your portrait", keywords = listOf("avatar", "picture", "photo"), block = "infantry.shell.config.userPortrait"),
)

/** What a [ThemeBlock] last applied, so a Reset (the user override gone) puts the declared default back. */
private class Applied { var accent = false; var dark = false; var blur = false }

/**
 * CONFIG-3: the theme block. It draws nothing; it reads its own node's config (`scope.config()`, injected) and sets
 * [Palette.theme], the one holder every composable reads, and the modal blur. A value is applied only once the user has
 * set it (or had, until Reset), so a theme saved by the old preference is not overwritten by a declared default.
 */
object ThemeBlock : Block(THEME_FILL_ID, THEME_FILL_ID) {
    @Composable
    override fun Render(scope: BlockScope) {
        val cfg = scope.config()
        val blur = LocalModalBlur.current
        val applied = remember { Applied() }
        val accent = if (cfg.overridden(ACCENT_KEY) || applied.accent) ThemeAccent.entries.firstOrNull { it.name == cfg.string(ACCENT_KEY) } else null
        val dark = if (cfg.overridden(DARK_KEY) || applied.dark) cfg.boolean(DARK_KEY) else null
        val wantBlur = if (cfg.overridden(BLUR_KEY) || applied.blur) cfg.boolean(BLUR_KEY) else null
        // AVATAR-PICKER-1: a key the user picked; unset keeps the window's own preference. The resolved value is already the default when the picked style is gone.
        val avatar = cfg.string(AVATAR_STYLE_KEY)?.takeIf { cfg.overridden(AVATAR_STYLE_KEY) }
        SideEffect {
            if (AvatarStyleChoice.chosen != avatar) AvatarStyleChoice.chosen = avatar
            val now = Palette.theme
            val next = ThemeSettings(accent ?: now.accent, dark ?: now.dark)
            if (next != now) Palette.theme = next
            if (wantBlur != null && wantBlur != blur.enabled) blur.enabled = wantBlur
            applied.accent = cfg.overridden(ACCENT_KEY)
            applied.dark = cfg.overridden(DARK_KEY)
            applied.blur = cfg.overridden(BLUR_KEY)
        }
    }
}

/** CONFIG-3: Infantry_Appearance (official), the theme slot and its settings, compiled in on web and Android (D9). */
val InfantryAppearance = InfantryModule(
    ModuleManifest(
        name = "Infantry_Appearance", version = "1.0.0", author = "Infantry AI",
        description = "Theme and blur: the pilot of manifest-driven settings.",
        fills = listOf(FillDeclaration(THEME_FILL_ID)),
        config = APPEARANCE_CONFIG,
    ),
    fills = mapOf(THEME_FILL_ID to ThemeBlock),
)

/**
 * CONFIG-3: the entry of Infantry_Appearance (module.json `entry`), an official module. Without it the app has the
 * default palette from its saved preference and the hand-written Theme and Blur sections.
 *  - Desktop loads it at run time from its signed jar (`official/Infantry_Appearance`) as a [UiPlugin].
 *  - Web and Android compile it in from the generated module list, which takes it as a [ModuleSource].
 */
class AppearancePlugin : UiPlugin, ModuleSource {
    override fun register(registrar: UiRegistrar) {
        registrar.fill(THEME_FILL_ID, ThemeBlock)
    }

    override fun modules(): List<InfantryModule> = listOf(InfantryAppearance)
}
