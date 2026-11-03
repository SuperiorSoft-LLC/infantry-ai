// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.shell.blocks

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.superiorsoftllc.infantry.ui.shell.LocalShellContext
import com.superiorsoftllc.infantry.ui.LocalToast
import com.superiorsoftllc.infantry.ui.ToastHost
import com.superiorsoftllc.infantry.ui.block.layout.Block
import com.superiorsoftllc.infantry.ui.block.layout.BlockScope
import com.superiorsoftllc.infantry.ui.block.layout.Place
import com.superiorsoftllc.infantry.ui.block.layout.RenderPlaced
import com.superiorsoftllc.infantry.ui.block.layout.RenderPlain

/**
 * BLOCKS-SHELL-1: the toasts, bottom right over the window; a tap opens what a toast is about.
 * TOAST-1: a container. Its children (a module's own toast blocks) draw over the dock after the core host, aligning
 * themselves in the dock's stack, under the controller's [com.superiorsoftllc.infantry.ui.ToastService] (`LocalToast`).
 * The service itself is provided at the shell root, so any block anywhere can toast.
 */
class ToastBlock(children: List<Block> = emptyList(), place: Place = Place()) :
    Block("infantry.shell.toast", "infantry.shell.toast", place, children) {
    @Composable
    override fun Render(scope: BlockScope) = with(scope) {
        val toast = LocalShellContext.current?.toast ?: return
        ToastHost(toast.events, onOpen = toast::open, modifier = Modifier.aligned(Alignment.BottomEnd))
        if (children.isNotEmpty()) CompositionLocalProvider(LocalToast provides toast.service) {
            box?.run { RenderPlaced(children) } ?: RenderPlain(children)
        }
    }

    override fun withChildren(children: List<Block>) = ToastBlock(children, place)
}

val ShellToast = ToastBlock()
