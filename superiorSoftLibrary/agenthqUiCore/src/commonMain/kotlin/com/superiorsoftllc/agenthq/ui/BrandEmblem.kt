package com.superiorsoftllc.agenthq.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import com.superiorsoftllc.agenthq.ui.generated.resources.Res
import kotlinx.coroutines.CancellationException
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.decodeToImageBitmap

private const val EMBLEM_PATH = "drawable/infantry_emblem.png"

/** Decoded once for every emblem; a failed load is cached as null (nothing drawn, same-size gap). Composition thread only. */
private var emblemCache: ImageBitmap? = null
private var emblemTried = false

@OptIn(ExperimentalResourceApi::class)
@Composable
private fun rememberEmblem(): ImageBitmap? {
    val bitmap by produceState(emblemCache) {
        if (emblemTried) { value = emblemCache; return@produceState }
        emblemCache = try {
            Res.readBytes(EMBLEM_PATH).decodeToImageBitmap()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        emblemTried = true
        value = emblemCache
    }
    return bitmap
}

/** The Infantry AI emblem at [size]; decorative (empty content description) because the product name is always beside it. */
@Composable
fun BrandEmblem(size: Dp, modifier: Modifier = Modifier) {
    val bitmap = rememberEmblem()
    if (bitmap == null) {
        Box(modifier.size(size))
    } else {
        Image(bitmap, contentDescription = null, modifier = modifier.size(size), contentScale = ContentScale.Fit)
    }
}
