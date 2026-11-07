// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.plugin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import com.superiorsoftllc.infantry.ui.LocalTabFactory
import com.superiorsoftllc.infantry.ui.shell.GatedConfig
import com.superiorsoftllc.infantry.ui.shell.LocalShellContext
import com.superiorsoftllc.infantry.ui.shell.ScopedShellContext

/**
 * CONFIG-FIELDS-4 (G8): draws [content] as [module], so the [LocalShellContext] its code reads answers `config` through
 * the `config.read` gate with the module as the reader. A core or official module, or the gate off, draws unchanged.
 *
 * PLUGIN-HOTSWAP-1: keyed by the module's load generation ([TabFactory.generation]): after a live swap of [module] its old
 * composition (remembered values made by the old class loader) is disposed and the new version composes afresh; a module
 * that was not swapped keeps the same key, so its state survives the swap of another.
 */
@Composable
internal fun ModuleScope(module: String?, content: @Composable () -> Unit) {
    val factory = LocalTabFactory.current
    key(module, module?.let(factory.generation) ?: 0) {
        val ctx = LocalShellContext.current
        if (module == null || ctx == null || !factory.configGated(module)) content()
        else {
            val scoped = remember(ctx, factory, module) { ScopedShellContext(ctx, GatedConfig(ctx.config) { factory.configReadAllows(module, it) }) }
            CompositionLocalProvider(LocalShellContext provides scoped, content = content)
        }
    }
}
