/*
 * The path data below is copied from the Material icons of the Android Open Source Project
 * (androidx.compose.material:material-icons-core / material-icons-extended 1.7.3).
 *
 * Copyright 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.superiorsoftllc.agenthq.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import com.superiorsoftllc.agenthq.ui.model.RoleIcons

/**
 * ROLE-ICON-1: the role icon set, keyed by the icon key a role carries on the wire (contract 1.93.0). Copied from the
 * Material sources exactly like [AhqIcons] (UX-ICON-1: no material-icons-extended jar). [RoleIcons.KEYS] is the order
 * the Edit role picker offers them in; this map must hold every one of those keys (AhqRoleIconsTest checks it).
 */
object AhqRoleIcons {
    /** The vector for [key], or null when this build has no such icon (the caller then paints the role's default). */
    fun vector(key: String): ImageVector? = byKey[key]?.value

    /** The icon a role paints: its stored key when this build knows it, else the default for the role's kind. */
    fun forRole(roleId: String?, icon: String?): ImageVector = vector(RoleIcons.resolve(roleId, icon)) ?: Person

    /** Material "Filled.Person". */
    val Person: ImageVector by lazy {
        icon(
            "Person",
            {
                moveTo(12.0f, 12.0f)
                curveToRelative(2.21f, 0.0f, 4.0f, -1.79f, 4.0f, -4.0f)
                reflectiveCurveToRelative(-1.79f, -4.0f, -4.0f, -4.0f)
                reflectiveCurveToRelative(-4.0f, 1.79f, -4.0f, 4.0f)
                reflectiveCurveToRelative(1.79f, 4.0f, 4.0f, 4.0f)
                close()
                moveTo(12.0f, 14.0f)
                curveToRelative(-2.67f, 0.0f, -8.0f, 1.34f, -8.0f, 4.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(16.0f)
                verticalLineToRelative(-2.0f)
                curveToRelative(0.0f, -2.66f, -5.33f, -4.0f, -8.0f, -4.0f)
                close()
            },
        )
    }

    /** Material "Filled.Assignment". */
    val Assignment: ImageVector by lazy {
        icon(
            "Assignment",
            {
                moveTo(19.0f, 3.0f)
                horizontalLineToRelative(-4.18f)
                curveTo(14.4f, 1.84f, 13.3f, 1.0f, 12.0f, 1.0f)
                curveToRelative(-1.3f, 0.0f, -2.4f, 0.84f, -2.82f, 2.0f)
                lineTo(5.0f, 3.0f)
                curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
                verticalLineToRelative(14.0f)
                curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                horizontalLineToRelative(14.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                lineTo(21.0f, 5.0f)
                curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
                close()
                moveTo(12.0f, 3.0f)
                curveToRelative(0.55f, 0.0f, 1.0f, 0.45f, 1.0f, 1.0f)
                reflectiveCurveToRelative(-0.45f, 1.0f, -1.0f, 1.0f)
                reflectiveCurveToRelative(-1.0f, -0.45f, -1.0f, -1.0f)
                reflectiveCurveToRelative(0.45f, -1.0f, 1.0f, -1.0f)
                close()
                moveTo(14.0f, 17.0f)
                lineTo(7.0f, 17.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineToRelative(7.0f)
                verticalLineToRelative(2.0f)
                close()
                moveTo(17.0f, 13.0f)
                lineTo(7.0f, 13.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineToRelative(10.0f)
                verticalLineToRelative(2.0f)
                close()
                moveTo(17.0f, 9.0f)
                lineTo(7.0f, 9.0f)
                lineTo(7.0f, 7.0f)
                horizontalLineToRelative(10.0f)
                verticalLineToRelative(2.0f)
                close()
            },
        )
    }

    /** Material "Filled.AccountTree". */
    val AccountTree: ImageVector by lazy {
        icon(
            "AccountTree",
            {
                moveTo(22.0f, 11.0f)
                verticalLineTo(3.0f)
                horizontalLineToRelative(-7.0f)
                verticalLineToRelative(3.0f)
                horizontalLineTo(9.0f)
                verticalLineTo(3.0f)
                horizontalLineTo(2.0f)
                verticalLineToRelative(8.0f)
                horizontalLineToRelative(7.0f)
                verticalLineTo(8.0f)
                horizontalLineToRelative(2.0f)
                verticalLineToRelative(10.0f)
                horizontalLineToRelative(4.0f)
                verticalLineToRelative(3.0f)
                horizontalLineToRelative(7.0f)
                verticalLineToRelative(-8.0f)
                horizontalLineToRelative(-7.0f)
                verticalLineToRelative(3.0f)
                horizontalLineToRelative(-2.0f)
                verticalLineTo(8.0f)
                horizontalLineToRelative(2.0f)
                verticalLineToRelative(3.0f)
                close()
            },
        )
    }

    /** Material "Filled.BugReport". */
    val BugReport: ImageVector by lazy {
        icon(
            "BugReport",
            {
                moveTo(20.0f, 8.0f)
                horizontalLineToRelative(-2.81f)
                curveToRelative(-0.45f, -0.78f, -1.07f, -1.45f, -1.82f, -1.96f)
                lineTo(17.0f, 4.41f)
                lineTo(15.59f, 3.0f)
                lineToRelative(-2.17f, 2.17f)
                curveTo(12.96f, 5.06f, 12.49f, 5.0f, 12.0f, 5.0f)
                curveToRelative(-0.49f, 0.0f, -0.96f, 0.06f, -1.41f, 0.17f)
                lineTo(8.41f, 3.0f)
                lineTo(7.0f, 4.41f)
                lineToRelative(1.62f, 1.63f)
                curveTo(7.88f, 6.55f, 7.26f, 7.22f, 6.81f, 8.0f)
                lineTo(4.0f, 8.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(2.09f)
                curveToRelative(-0.05f, 0.33f, -0.09f, 0.66f, -0.09f, 1.0f)
                verticalLineToRelative(1.0f)
                lineTo(4.0f, 12.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(2.0f)
                verticalLineToRelative(1.0f)
                curveToRelative(0.0f, 0.34f, 0.04f, 0.67f, 0.09f, 1.0f)
                lineTo(4.0f, 16.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(2.81f)
                curveToRelative(1.04f, 1.79f, 2.97f, 3.0f, 5.19f, 3.0f)
                reflectiveCurveToRelative(4.15f, -1.21f, 5.19f, -3.0f)
                lineTo(20.0f, 18.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineToRelative(-2.09f)
                curveToRelative(0.05f, -0.33f, 0.09f, -0.66f, 0.09f, -1.0f)
                verticalLineToRelative(-1.0f)
                horizontalLineToRelative(2.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineToRelative(-2.0f)
                verticalLineToRelative(-1.0f)
                curveToRelative(0.0f, -0.34f, -0.04f, -0.67f, -0.09f, -1.0f)
                lineTo(20.0f, 10.0f)
                lineTo(20.0f, 8.0f)
                close()
                moveTo(14.0f, 16.0f)
                horizontalLineToRelative(-4.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineToRelative(4.0f)
                verticalLineToRelative(2.0f)
                close()
                moveTo(14.0f, 12.0f)
                horizontalLineToRelative(-4.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineToRelative(4.0f)
                verticalLineToRelative(2.0f)
                close()
            },
        )
    }

    /** Material "Filled.Layers". */
    val Layers: ImageVector by lazy {
        icon(
            "Layers",
            {
                moveTo(11.99f, 18.54f)
                lineToRelative(-7.37f, -5.73f)
                lineTo(3.0f, 14.07f)
                lineToRelative(9.0f, 7.0f)
                lineToRelative(9.0f, -7.0f)
                lineToRelative(-1.63f, -1.27f)
                lineToRelative(-7.38f, 5.74f)
                close()
                moveTo(12.0f, 16.0f)
                lineToRelative(7.36f, -5.73f)
                lineTo(21.0f, 9.0f)
                lineToRelative(-9.0f, -7.0f)
                lineToRelative(-9.0f, 7.0f)
                lineToRelative(1.63f, 1.27f)
                lineTo(12.0f, 16.0f)
                close()
            },
        )
    }

    /** Material "Filled.Storage". */
    val Storage: ImageVector by lazy {
        icon(
            "Storage",
            {
                moveTo(2.0f, 20.0f)
                horizontalLineToRelative(20.0f)
                verticalLineToRelative(-4.0f)
                lineTo(2.0f, 16.0f)
                verticalLineToRelative(4.0f)
                close()
                moveTo(4.0f, 17.0f)
                horizontalLineToRelative(2.0f)
                verticalLineToRelative(2.0f)
                lineTo(4.0f, 19.0f)
                verticalLineToRelative(-2.0f)
                close()
                moveTo(2.0f, 4.0f)
                verticalLineToRelative(4.0f)
                horizontalLineToRelative(20.0f)
                lineTo(22.0f, 4.0f)
                lineTo(2.0f, 4.0f)
                close()
                moveTo(6.0f, 7.0f)
                lineTo(4.0f, 7.0f)
                lineTo(4.0f, 5.0f)
                horizontalLineToRelative(2.0f)
                verticalLineToRelative(2.0f)
                close()
                moveTo(2.0f, 14.0f)
                horizontalLineToRelative(20.0f)
                verticalLineToRelative(-4.0f)
                lineTo(2.0f, 10.0f)
                verticalLineToRelative(4.0f)
                close()
                moveTo(4.0f, 11.0f)
                horizontalLineToRelative(2.0f)
                verticalLineToRelative(2.0f)
                lineTo(4.0f, 13.0f)
                verticalLineToRelative(-2.0f)
                close()
            },
        )
    }

    /** Material "Filled.Web". */
    val Web: ImageVector by lazy {
        icon(
            "Web",
            {
                moveTo(20.0f, 4.0f)
                lineTo(4.0f, 4.0f)
                curveToRelative(-1.1f, 0.0f, -1.99f, 0.9f, -1.99f, 2.0f)
                lineTo(2.0f, 18.0f)
                curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                horizontalLineToRelative(16.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                lineTo(22.0f, 6.0f)
                curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
                close()
                moveTo(15.0f, 18.0f)
                lineTo(4.0f, 18.0f)
                verticalLineToRelative(-4.0f)
                horizontalLineToRelative(11.0f)
                verticalLineToRelative(4.0f)
                close()
                moveTo(15.0f, 13.0f)
                lineTo(4.0f, 13.0f)
                lineTo(4.0f, 9.0f)
                horizontalLineToRelative(11.0f)
                verticalLineToRelative(4.0f)
                close()
                moveTo(20.0f, 18.0f)
                horizontalLineToRelative(-4.0f)
                lineTo(16.0f, 9.0f)
                horizontalLineToRelative(4.0f)
                verticalLineToRelative(9.0f)
                close()
            },
        )
    }

    /** Material "Filled.Cloud". */
    val Cloud: ImageVector by lazy {
        icon(
            "Cloud",
            {
                moveTo(19.35f, 10.04f)
                curveTo(18.67f, 6.59f, 15.64f, 4.0f, 12.0f, 4.0f)
                curveTo(9.11f, 4.0f, 6.6f, 5.64f, 5.35f, 8.04f)
                curveTo(2.34f, 8.36f, 0.0f, 10.91f, 0.0f, 14.0f)
                curveToRelative(0.0f, 3.31f, 2.69f, 6.0f, 6.0f, 6.0f)
                horizontalLineToRelative(13.0f)
                curveToRelative(2.76f, 0.0f, 5.0f, -2.24f, 5.0f, -5.0f)
                curveToRelative(0.0f, -2.64f, -2.05f, -4.78f, -4.65f, -4.96f)
                close()
            },
        )
    }

    /** Material "Filled.Shield". */
    val Shield: ImageVector by lazy {
        icon(
            "Shield",
            {
                moveTo(12.0f, 1.0f)
                lineTo(3.0f, 5.0f)
                verticalLineToRelative(6.0f)
                curveToRelative(0.0f, 5.55f, 3.84f, 10.74f, 9.0f, 12.0f)
                curveToRelative(5.16f, -1.26f, 9.0f, -6.45f, 9.0f, -12.0f)
                verticalLineTo(5.0f)
                lineToRelative(-9.0f, -4.0f)
                close()
            },
        )
    }

    /** Material "Filled.Architecture". */
    val Architecture: ImageVector by lazy {
        icon(
            "Architecture",
            {
                moveTo(6.36f, 18.78f)
                lineTo(6.61f, 21.0f)
                lineToRelative(1.62f, -1.54f)
                lineToRelative(2.77f, -7.6f)
                curveToRelative(-0.68f, -0.17f, -1.28f, -0.51f, -1.77f, -0.98f)
                lineTo(6.36f, 18.78f)
                close()
            },
            {
                moveTo(14.77f, 10.88f)
                curveToRelative(-0.49f, 0.47f, -1.1f, 0.81f, -1.77f, 0.98f)
                lineToRelative(2.77f, 7.6f)
                lineTo(17.39f, 21.0f)
                lineToRelative(0.26f, -2.22f)
                lineTo(14.77f, 10.88f)
                close()
            },
            {
                moveTo(15.0f, 8.0f)
                curveToRelative(0.0f, -1.3f, -0.84f, -2.4f, -2.0f, -2.82f)
                verticalLineTo(3.0f)
                horizontalLineToRelative(-2.0f)
                verticalLineToRelative(2.18f)
                curveTo(9.84f, 5.6f, 9.0f, 6.7f, 9.0f, 8.0f)
                curveToRelative(0.0f, 1.66f, 1.34f, 3.0f, 3.0f, 3.0f)
                reflectiveCurveTo(15.0f, 9.66f, 15.0f, 8.0f)
                close()
                moveTo(12.0f, 9.0f)
                curveToRelative(-0.55f, 0.0f, -1.0f, -0.45f, -1.0f, -1.0f)
                curveToRelative(0.0f, -0.55f, 0.45f, -1.0f, 1.0f, -1.0f)
                reflectiveCurveToRelative(1.0f, 0.45f, 1.0f, 1.0f)
                curveTo(13.0f, 8.55f, 12.55f, 9.0f, 12.0f, 9.0f)
                close()
            },
        )
    }

    /** Material "Filled.PhoneAndroid". */
    val PhoneAndroid: ImageVector by lazy {
        icon(
            "PhoneAndroid",
            {
                moveTo(16.0f, 1.0f)
                lineTo(8.0f, 1.0f)
                curveTo(6.34f, 1.0f, 5.0f, 2.34f, 5.0f, 4.0f)
                verticalLineToRelative(16.0f)
                curveToRelative(0.0f, 1.66f, 1.34f, 3.0f, 3.0f, 3.0f)
                horizontalLineToRelative(8.0f)
                curveToRelative(1.66f, 0.0f, 3.0f, -1.34f, 3.0f, -3.0f)
                lineTo(19.0f, 4.0f)
                curveToRelative(0.0f, -1.66f, -1.34f, -3.0f, -3.0f, -3.0f)
                close()
                moveTo(14.0f, 21.0f)
                horizontalLineToRelative(-4.0f)
                verticalLineToRelative(-1.0f)
                horizontalLineToRelative(4.0f)
                verticalLineToRelative(1.0f)
                close()
                moveTo(17.25f, 18.0f)
                lineTo(6.75f, 18.0f)
                lineTo(6.75f, 4.0f)
                horizontalLineToRelative(10.5f)
                verticalLineToRelative(14.0f)
                close()
            },
        )
    }

    /** Material "Filled.Campaign". */
    val Campaign: ImageVector by lazy {
        icon(
            "Campaign",
            {
                moveTo(18.0f, 11.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(4.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineToRelative(-4.0f)
                close()
                moveTo(16.0f, 17.61f)
                curveToRelative(0.96f, 0.71f, 2.21f, 1.65f, 3.2f, 2.39f)
                curveToRelative(0.4f, -0.53f, 0.8f, -1.07f, 1.2f, -1.6f)
                curveToRelative(-0.99f, -0.74f, -2.24f, -1.68f, -3.2f, -2.4f)
                curveToRelative(-0.4f, 0.54f, -0.8f, 1.08f, -1.2f, 1.61f)
                close()
                moveTo(20.4f, 5.6f)
                curveToRelative(-0.4f, -0.53f, -0.8f, -1.07f, -1.2f, -1.6f)
                curveToRelative(-0.99f, 0.74f, -2.24f, 1.68f, -3.2f, 2.4f)
                curveToRelative(0.4f, 0.53f, 0.8f, 1.07f, 1.2f, 1.6f)
                curveToRelative(0.96f, -0.72f, 2.21f, -1.65f, 3.2f, -2.4f)
                close()
                moveTo(4.0f, 9.0f)
                curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
                verticalLineToRelative(2.0f)
                curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                horizontalLineToRelative(1.0f)
                verticalLineToRelative(4.0f)
                horizontalLineToRelative(2.0f)
                verticalLineToRelative(-4.0f)
                horizontalLineToRelative(1.0f)
                lineToRelative(5.0f, 3.0f)
                lineTo(13.0f, 6.0f)
                lineTo(8.0f, 9.0f)
                lineTo(4.0f, 9.0f)
                close()
                moveTo(15.5f, 12.0f)
                curveToRelative(0.0f, -1.33f, -0.58f, -2.53f, -1.5f, -3.35f)
                verticalLineToRelative(6.69f)
                curveToRelative(0.92f, -0.81f, 1.5f, -2.01f, 1.5f, -3.34f)
                close()
            },
        )
    }

    /** Material "Filled.TravelExplore". */
    val TravelExplore: ImageVector by lazy {
        icon(
            "TravelExplore",
            {
                moveTo(19.3f, 16.9f)
                curveToRelative(0.4f, -0.7f, 0.7f, -1.5f, 0.7f, -2.4f)
                curveToRelative(0.0f, -2.5f, -2.0f, -4.5f, -4.5f, -4.5f)
                reflectiveCurveTo(11.0f, 12.0f, 11.0f, 14.5f)
                reflectiveCurveToRelative(2.0f, 4.5f, 4.5f, 4.5f)
                curveToRelative(0.9f, 0.0f, 1.7f, -0.3f, 2.4f, -0.7f)
                lineToRelative(3.2f, 3.2f)
                lineToRelative(1.4f, -1.4f)
                lineTo(19.3f, 16.9f)
                close()
                moveTo(15.5f, 17.0f)
                curveToRelative(-1.4f, 0.0f, -2.5f, -1.1f, -2.5f, -2.5f)
                reflectiveCurveToRelative(1.1f, -2.5f, 2.5f, -2.5f)
                reflectiveCurveToRelative(2.5f, 1.1f, 2.5f, 2.5f)
                reflectiveCurveTo(16.9f, 17.0f, 15.5f, 17.0f)
                close()
                moveTo(12.0f, 20.0f)
                verticalLineToRelative(2.0f)
                curveTo(6.48f, 22.0f, 2.0f, 17.52f, 2.0f, 12.0f)
                curveTo(2.0f, 6.48f, 6.48f, 2.0f, 12.0f, 2.0f)
                curveToRelative(4.84f, 0.0f, 8.87f, 3.44f, 9.8f, 8.0f)
                horizontalLineToRelative(-2.07f)
                curveToRelative(-0.64f, -2.46f, -2.4f, -4.47f, -4.73f, -5.41f)
                verticalLineTo(5.0f)
                curveToRelative(0.0f, 1.1f, -0.9f, 2.0f, -2.0f, 2.0f)
                horizontalLineToRelative(-2.0f)
                verticalLineToRelative(2.0f)
                curveToRelative(0.0f, 0.55f, -0.45f, 1.0f, -1.0f, 1.0f)
                horizontalLineTo(8.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(2.0f)
                verticalLineToRelative(3.0f)
                horizontalLineTo(9.0f)
                lineToRelative(-4.79f, -4.79f)
                curveTo(4.08f, 10.79f, 4.0f, 11.38f, 4.0f, 12.0f)
                curveTo(4.0f, 16.41f, 7.59f, 20.0f, 12.0f, 20.0f)
                close()
            },
        )
    }

    /** Material "Filled.Translate". */
    val Translate: ImageVector by lazy {
        icon(
            "Translate",
            {
                moveTo(12.87f, 15.07f)
                lineToRelative(-2.54f, -2.51f)
                lineToRelative(0.03f, -0.03f)
                curveToRelative(1.74f, -1.94f, 2.98f, -4.17f, 3.71f, -6.53f)
                lineTo(17.0f, 6.0f)
                lineTo(17.0f, 4.0f)
                horizontalLineToRelative(-7.0f)
                lineTo(10.0f, 2.0f)
                lineTo(8.0f, 2.0f)
                verticalLineToRelative(2.0f)
                lineTo(1.0f, 4.0f)
                verticalLineToRelative(1.99f)
                horizontalLineToRelative(11.17f)
                curveTo(11.5f, 7.92f, 10.44f, 9.75f, 9.0f, 11.35f)
                curveTo(8.07f, 10.32f, 7.3f, 9.19f, 6.69f, 8.0f)
                horizontalLineToRelative(-2.0f)
                curveToRelative(0.73f, 1.63f, 1.73f, 3.17f, 2.98f, 4.56f)
                lineToRelative(-5.09f, 5.02f)
                lineTo(4.0f, 19.0f)
                lineToRelative(5.0f, -5.0f)
                lineToRelative(3.11f, 3.11f)
                lineToRelative(0.76f, -2.04f)
                close()
                moveTo(18.5f, 10.0f)
                horizontalLineToRelative(-2.0f)
                lineTo(12.0f, 22.0f)
                horizontalLineToRelative(2.0f)
                lineToRelative(1.12f, -3.0f)
                horizontalLineToRelative(4.75f)
                lineTo(21.0f, 22.0f)
                horizontalLineToRelative(2.0f)
                lineToRelative(-4.5f, -12.0f)
                close()
                moveTo(15.88f, 17.0f)
                lineToRelative(1.62f, -4.33f)
                lineTo(19.12f, 17.0f)
                horizontalLineToRelative(-3.24f)
                close()
            },
        )
    }

    /** Material "Filled.Lightbulb". */
    val Lightbulb: ImageVector by lazy {
        icon(
            "Lightbulb",
            {
                moveTo(9.0f, 21.0f)
                curveToRelative(0.0f, 0.5f, 0.4f, 1.0f, 1.0f, 1.0f)
                horizontalLineToRelative(4.0f)
                curveToRelative(0.6f, 0.0f, 1.0f, -0.5f, 1.0f, -1.0f)
                verticalLineToRelative(-1.0f)
                lineTo(9.0f, 20.0f)
                verticalLineToRelative(1.0f)
                close()
                moveTo(12.0f, 2.0f)
                curveTo(8.1f, 2.0f, 5.0f, 5.1f, 5.0f, 9.0f)
                curveToRelative(0.0f, 2.4f, 1.2f, 4.5f, 3.0f, 5.7f)
                lineTo(8.0f, 17.0f)
                curveToRelative(0.0f, 0.5f, 0.4f, 1.0f, 1.0f, 1.0f)
                horizontalLineToRelative(6.0f)
                curveToRelative(0.6f, 0.0f, 1.0f, -0.5f, 1.0f, -1.0f)
                verticalLineToRelative(-2.3f)
                curveToRelative(1.8f, -1.3f, 3.0f, -3.4f, 3.0f, -5.7f)
                curveToRelative(0.0f, -3.9f, -3.1f, -7.0f, -7.0f, -7.0f)
                close()
            },
        )
    }

    /** Material "Filled.SmartToy". */
    val SmartToy: ImageVector by lazy {
        icon(
            "SmartToy",
            {
                moveTo(20.0f, 9.0f)
                verticalLineTo(7.0f)
                curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
                horizontalLineToRelative(-3.0f)
                curveToRelative(0.0f, -1.66f, -1.34f, -3.0f, -3.0f, -3.0f)
                reflectiveCurveTo(9.0f, 3.34f, 9.0f, 5.0f)
                horizontalLineTo(6.0f)
                curveTo(4.9f, 5.0f, 4.0f, 5.9f, 4.0f, 7.0f)
                verticalLineToRelative(2.0f)
                curveToRelative(-1.66f, 0.0f, -3.0f, 1.34f, -3.0f, 3.0f)
                curveToRelative(0.0f, 1.66f, 1.34f, 3.0f, 3.0f, 3.0f)
                verticalLineToRelative(4.0f)
                curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                horizontalLineToRelative(12.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                verticalLineToRelative(-4.0f)
                curveToRelative(1.66f, 0.0f, 3.0f, -1.34f, 3.0f, -3.0f)
                curveTo(23.0f, 10.34f, 21.66f, 9.0f, 20.0f, 9.0f)
                close()
                moveTo(7.5f, 11.5f)
                curveTo(7.5f, 10.67f, 8.17f, 10.0f, 9.0f, 10.0f)
                reflectiveCurveToRelative(1.5f, 0.67f, 1.5f, 1.5f)
                reflectiveCurveTo(9.83f, 13.0f, 9.0f, 13.0f)
                reflectiveCurveTo(7.5f, 12.33f, 7.5f, 11.5f)
                close()
                moveTo(16.0f, 17.0f)
                horizontalLineTo(8.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineToRelative(8.0f)
                verticalLineTo(17.0f)
                close()
                moveTo(15.0f, 13.0f)
                curveToRelative(-0.83f, 0.0f, -1.5f, -0.67f, -1.5f, -1.5f)
                reflectiveCurveTo(14.17f, 10.0f, 15.0f, 10.0f)
                reflectiveCurveToRelative(1.5f, 0.67f, 1.5f, 1.5f)
                reflectiveCurveTo(15.83f, 13.0f, 15.0f, 13.0f)
                close()
            },
        )
    }

    /** Material "Filled.SupportAgent". */
    val SupportAgent: ImageVector by lazy {
        icon(
            "SupportAgent",
            {
                moveTo(21.0f, 12.22f)
                curveTo(21.0f, 6.73f, 16.74f, 3.0f, 12.0f, 3.0f)
                curveToRelative(-4.69f, 0.0f, -9.0f, 3.65f, -9.0f, 9.28f)
                curveTo(2.4f, 12.62f, 2.0f, 13.26f, 2.0f, 14.0f)
                verticalLineToRelative(2.0f)
                curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                horizontalLineToRelative(1.0f)
                verticalLineToRelative(-6.1f)
                curveToRelative(0.0f, -3.87f, 3.13f, -7.0f, 7.0f, -7.0f)
                reflectiveCurveToRelative(7.0f, 3.13f, 7.0f, 7.0f)
                verticalLineTo(19.0f)
                horizontalLineToRelative(-8.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(8.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                verticalLineToRelative(-1.22f)
                curveToRelative(0.59f, -0.31f, 1.0f, -0.92f, 1.0f, -1.64f)
                verticalLineToRelative(-2.3f)
                curveTo(22.0f, 13.14f, 21.59f, 12.53f, 21.0f, 12.22f)
                close()
            },
            {
                moveTo(9.0f, 13.0f)
                moveToRelative(-1.0f, 0.0f)
                arcToRelative(1.0f, 1.0f, 0.0f, true, true, 2.0f, 0.0f)
                arcToRelative(1.0f, 1.0f, 0.0f, true, true, -2.0f, 0.0f)
            },
            {
                moveTo(15.0f, 13.0f)
                moveToRelative(-1.0f, 0.0f)
                arcToRelative(1.0f, 1.0f, 0.0f, true, true, 2.0f, 0.0f)
                arcToRelative(1.0f, 1.0f, 0.0f, true, true, -2.0f, 0.0f)
            },
            {
                moveTo(18.0f, 11.03f)
                curveTo(17.52f, 8.18f, 15.04f, 6.0f, 12.05f, 6.0f)
                curveToRelative(-3.03f, 0.0f, -6.29f, 2.51f, -6.03f, 6.45f)
                curveToRelative(2.47f, -1.01f, 4.33f, -3.21f, 4.86f, -5.89f)
                curveTo(12.19f, 9.19f, 14.88f, 11.0f, 18.0f, 11.03f)
                close()
            },
        )
    }

    /** Material "Filled.Code". */
    val Code: ImageVector by lazy {
        icon(
            "Code",
            {
                moveTo(9.4f, 16.6f)
                lineTo(4.8f, 12.0f)
                lineToRelative(4.6f, -4.6f)
                lineTo(8.0f, 6.0f)
                lineToRelative(-6.0f, 6.0f)
                lineToRelative(6.0f, 6.0f)
                lineToRelative(1.4f, -1.4f)
                close()
                moveTo(14.6f, 16.6f)
                lineToRelative(4.6f, -4.6f)
                lineToRelative(-4.6f, -4.6f)
                lineTo(16.0f, 6.0f)
                lineToRelative(6.0f, 6.0f)
                lineToRelative(-6.0f, 6.0f)
                lineToRelative(-1.4f, -1.4f)
                close()
            },
        )
    }

    private val byKey: Map<String, Lazy<ImageVector>> = mapOf(
        "person" to lazy { Person },
        "assignment" to lazy { Assignment },
        "account-tree" to lazy { AccountTree },
        "bug-report" to lazy { BugReport },
        "layers" to lazy { Layers },
        "storage" to lazy { Storage },
        "web" to lazy { Web },
        "cloud" to lazy { Cloud },
        "shield" to lazy { Shield },
        "architecture" to lazy { Architecture },
        "phone-android" to lazy { PhoneAndroid },
        "campaign" to lazy { Campaign },
        "travel-explore" to lazy { TravelExplore },
        "translate" to lazy { Translate },
        "lightbulb" to lazy { Lightbulb },
        "smart-toy" to lazy { SmartToy },
        "support-agent" to lazy { SupportAgent },
        "code" to lazy { Code },
    )

    /** Same grid, viewport and fill as Material's `materialIcon { materialPath { } }`; one path per [paths] entry. */
    private fun icon(name: String, vararg paths: PathBuilder.() -> Unit): ImageVector {
        val builder = ImageVector.Builder(
            name = "AhqRoleIcons.$name", defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f, autoMirror = false,
        )
        for (pathData in paths) {
            builder.path(
                fill = SolidColor(Color.Black), fillAlpha = 1f, stroke = null, strokeAlpha = 1f, strokeLineWidth = 1f,
                strokeLineCap = StrokeCap.Butt, strokeLineJoin = StrokeJoin.Bevel, strokeLineMiter = 1f,
                pathFillType = PathFillType.NonZero, pathBuilder = pathData,
            )
        }
        return builder.build()
    }
}
