/*
 * Material Mascot — a living bot avatar for Android apps.
 * Copyright (C) 2026 Max Master
 * Vendored into Infantry AI (see NOTICE). Copyright (c) SuperiorSoft LLC for the port.
 * SPDX-License-Identifier: MIT
 */

package com.maxmaster.materialmascot.engine

/*
 * Measured ear geometry, produced by tools/trace_ears.py from the reference
 * art — the same approach as the body silhouettes: measure the shape once and
 * ship the numbers, rather than hand-tuning bezier control points.
 *
 * The reference draws the pair sitting on a head that is not itself drawn.
 * Fitting a circle to the two base curves recovers it to 0.6% (rms 1.5px on
 * R=242), which is what makes this representation possible: the ears are not a
 * separate shape placed near a body, they are a RADIAL PROFILE of the same
 * head — for each angle off the crown, how far the silhouette stands out, in
 * head radii. Rebuilding that as max(head, ear) reproduces the traced art to
 * IoU 0.991.
 *
 * MaterialBot unions this into the live body profile, so the ears cannot come
 * off (there is only ever one outline) and a smooth union makes the join flow
 * instead of creasing.
 */

/** Angle off the crown where the traced ear begins, in degrees. */
internal const val EAR_FROM_DEG: Float = 8.0f

/** Angular step between [EAR_PROFILE] samples, in degrees. */
internal const val EAR_STEP_DEG: Float = 1.0f

/**
 * Silhouette radius in head radii, sampled every [EAR_STEP_DEG] from
 * [EAR_FROM_DEG] off the crown. The reference pair is symmetric to within a
 * pixel (both tips at 28.3 degrees, both reaching 1.691), so one profile serves
 * both ears; the left mirrors it.
 */
internal val EAR_PROFILE: FloatArray = floatArrayOf(
    0.0000f, 1.0989f, 1.1357f, 1.1736f, 1.2029f, 1.2376f, 1.2685f, 1.3050f,
    1.3367f, 1.3700f, 1.3999f, 1.4301f, 1.4608f, 1.4933f, 1.5264f, 1.5599f,
    1.5993f, 1.6299f, 1.6628f, 1.6870f, 1.6913f, 1.6896f, 1.6780f, 1.6577f,
    1.6358f, 1.6144f, 1.5901f, 1.5673f, 1.5450f, 1.5199f, 1.4927f, 1.4750f,
    1.4488f, 1.4262f, 1.4040f, 1.3822f, 1.3639f, 1.3372f, 1.3197f, 1.2997f,
    1.2772f, 1.2579f, 1.2389f, 1.2204f, 1.2048f, 1.1871f, 1.1664f, 1.1519f,
    1.1320f, 1.1148f, 1.0921f, 1.0756f, 1.0557f, 1.0250f, 0.0000f
)

/** How far the tips stand from the head centre, in head radii. */
internal const val EAR_TIP_REACH: Float = 1.6913f
