// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * SPLASH-ALL-1: the start-up splash of the desktop and Android shells, drawn to match the web's index.html splash
 * (ticket c302511d): the emblem breathing on #111, a red bar sliding along a dark track, "Loading Infantry AI…".
 * The web keeps its own HTML splash (it must paint before the wasm arrives) and never mounts a [SplashHost].
 */
const val SPLASH_TAG = "app-splash"

/** The animation timings; every one can be changed by the host. Nothing here delays the dismissal. */
data class SplashTiming(
    /** One pass of the red segment along the track (web: 1.2 s). */
    val slideMillis: Int = 1200,
    /** One breath of the emblem, out and back (web: 2.4 s). */
    val breatheMillis: Int = 2400,
    /** The fade once the first real screen is ready (web: .35 s). */
    val fadeOutMillis: Int = 350,
)

/** Whether the first real screen is up. One per window, owned by [SplashHost]; screens report through [SplashReady]. */
@Stable
class SplashState {
    var ready by mutableStateOf(false)
        private set

    fun markReady() { ready = true }
}

/** Null outside a [SplashHost] (the web), where [SplashReady] does nothing. */
val LocalSplash = staticCompositionLocalOf<SplashState?> { null }

/**
 * [content] under the splash until a screen in it calls [SplashReady]; the splash then fades out and never comes back
 * for this window (later reconnects show the app's own connection state).
 */
@Composable
fun SplashHost(
    state: SplashState = remember { SplashState() },
    timing: SplashTiming = SplashTiming(),
    content: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalSplash provides state) { content() }
        AnimatedVisibility(!state.ready, exit = fadeOut(tween(timing.fadeOutMillis))) {
            SplashScreen(timing)
        }
    }
}

/**
 * Called by the first real screen. [ready] false waits (the shell while it is still connecting); true dismisses the
 * splash once that screen has drawn a frame under it, so the fade reveals the screen, not a blank window.
 */
@Composable
fun SplashReady(ready: Boolean = true) {
    val splash = LocalSplash.current ?: return
    LaunchedEffect(splash, ready) {
        if (!ready || splash.ready) return@LaunchedEffect
        withFrameNanos { }
        splash.markReady()
    }
}

private val SplashBackground = Color(0xFF111111)
private val SplashText = Color(0xFFBBBBBB)
private val SplashTrack = Color(0xFF2A2A2A)
private val SplashRed = Color(0xFFE53935)

/** The splash itself: emblem, loader bar and message, centred. On the undecorated desktop window it drags the window. */
@Composable
fun SplashScreen(timing: SplashTiming = SplashTiming(), modifier: Modifier = Modifier) {
    val motion = rememberInfiniteTransition(label = "splash")
    val breath by motion.animateFloat(
        initialValue = 1f, targetValue = 0.8f,
        animationSpec = infiniteRepeatable(tween(timing.breatheMillis / 2, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breathe",
    )
    // Web keyframes: translateX(-100%) to translateX(250%) of a segment 40% of the track wide.
    val slide by motion.animateFloat(
        initialValue = -1f, targetValue = 2.5f,
        animationSpec = infiniteRepeatable(tween(timing.slideMillis, easing = FastOutSlowInEasing), RepeatMode.Restart),
        label = "slide",
    )
    val dragArea = LocalWindowChrome.current?.dragArea ?: Modifier
    BoxWithConstraints(
        modifier.fillMaxSize().background(SplashBackground).then(dragArea)
            .semantics { liveRegion = LiveRegionMode.Polite; contentDescription = "Loading Infantry AI" }
            .testTag(SPLASH_TAG),
        contentAlignment = Alignment.Center,
    ) {
        // Web: min(176px, 40vw) emblem, min(220px, 60vw) bar.
        val emblem = minOf(176.dp, maxWidth * 0.4f)
        val bar = minOf(220.dp, maxWidth * 0.6f)
        Column(
            Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            BrandEmblem(emblem, Modifier.alpha(breath))
            Box(Modifier.width(bar).height(3.dp).clip(RoundedCornerShape(2.dp)).background(SplashTrack)) {
                Box(
                    Modifier.fillMaxHeight().fillMaxWidth(0.4f)
                        .graphicsLayer { translationX = size.width * slide }
                        .clip(RoundedCornerShape(2.dp)).background(SplashRed),
                )
            }
            Text("Loading Infantry AI…", color = SplashText, fontSize = 15.sp)
        }
    }
}
