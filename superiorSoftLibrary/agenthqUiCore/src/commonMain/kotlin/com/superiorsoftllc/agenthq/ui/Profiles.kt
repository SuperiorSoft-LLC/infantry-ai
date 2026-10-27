package com.superiorsoftllc.agenthq.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.agenthq.ui.model.AppState
import com.superiorsoftllc.agenthq.ui.model.PickedModel
import com.superiorsoftllc.agenthq.ui.net.CommandResult
import com.superiorsoftllc.agenthq.ui.net.Controller
import com.superiorsoftllc.agenthq.ui.protocol.AgentProfile
import kotlinx.coroutines.launch


/**
 * PICKER-ALL-2: the harness + model of a profile form, through the shared [ExecutorPicker]. [provider] null is the
 * daemon's default harness and is kept null on Save until the user picks one; [model] "" is the harness default.
 */
// S4 bridge (docs/SHELL-LEAKS.md): public only until Roles moves to the app UI (S4b); Infantry_Profiles uses it.
@Composable
fun ProfileExecutorField(state: AppState, key: Any?, provider: String?, model: String, onChange: (String?, String) -> Unit) {
    val stored = (provider ?: state.providers.firstOrNull { it.default }?.id)?.let { PickedModel(it, model.ifEmpty { null }) }
    SeededExecutorPicker(
        state.providers, state.modelCatalog, stored, key, PROFILE_EXECUTOR_TAGS,
        onPick = { picked -> onChange(picked?.provider ?: provider, picked?.model.orEmpty()) },
    )
}
