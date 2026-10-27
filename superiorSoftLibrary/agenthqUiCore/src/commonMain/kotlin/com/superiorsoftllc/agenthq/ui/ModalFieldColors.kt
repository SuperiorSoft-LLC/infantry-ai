package com.superiorsoftllc.agenthq.ui

import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable

// S4c: the modal field colours stay in core (the executor picker and the searchable select use them); the New agent dialog is app UI.

/**
 * The theme's outline is the panel border colour, which vanishes on the dialog surface: give modal fields a visible rest
 * border and an accent focus border. THEME-1: both come from the live colour scheme, so they follow the chosen accent and mode.
 */
@Composable
fun visibleBorderFieldColors() = OutlinedTextFieldDefaults.colors(
    unfocusedBorderColor = MaterialTheme.colorScheme.onSurfaceVariant, focusedBorderColor = MaterialTheme.colorScheme.primary,
)
