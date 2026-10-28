package com.superiorsoftllc.agenthq.plugins.official.pods

import com.superiorsoftllc.agenthq.ui.*

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.agenthq.ui.model.AppState
import com.superiorsoftllc.agenthq.ui.model.parseNodes
import com.superiorsoftllc.agenthq.ui.shell.PodsContext
import kotlinx.coroutines.launch

/** The window's pods ([AppState.pods]) and the submit / load / cancel requests, through [pods] (RUNTIME-12). */
@Composable
internal fun PodsPane(state: AppState, pods: PodsContext) {
    val actionScope = rememberCoroutineScope()
    var goal by remember { mutableStateOf("") }
    var nodesText by remember { mutableStateOf("") }
    var podId by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (state.pods.isEmpty()) Text("No pods yet. Submit one below, or load one by id.", color = Palette.Muted)
        state.pods.values.sortedByDescending { it.pod.createdAt }.forEach { p ->
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Palette.Raised).padding(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(p.pod.goal, color = Palette.Text, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 2)
                    Chip(p.pod.state, podColor(p.pod.state))
                    if (p.pod.endedAt == null) {
                        TextButton(onClick = { actionScope.launch { pods.cancel(p.pod.id) } }) { Text("Cancel", color = Palette.Danger) }
                    }
                }
                Text(p.pod.id, color = Palette.Muted, fontFamily = Mono, fontSize = 10.sp)
                Spacer(Modifier.height(6.dp))
                p.nodes.sortedBy { it.specIndex }.forEach { n ->
                    val blocked = n.state == "PENDING" && p.pod.endedAt == null
                    val runs = p.runs[n.id].orEmpty()
                    Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(nodeColor(n.state)))
                        Spacer(Modifier.width(8.dp))
                        Text(n.name, color = Palette.Text, fontSize = 12.sp, modifier = Modifier.width(110.dp), maxLines = 1)
                        Chip(if (blocked) "blocked / queued" else n.state.lowercase(), nodeColor(n.state))
                        Spacer(Modifier.width(6.dp))
                        Text("try ${n.attempts}/${n.maxAttempts}", color = Palette.Muted, fontSize = 10.sp)
                        runs.lastOrNull()?.sessionId?.takeIf { it in state.agents }?.let { sid ->
                            TextButton(onClick = { pods.openAgent(sid) }) { Text("agent", fontSize = 11.sp) }
                        }
                    }
                    n.detail?.takeIf { it.isNotBlank() }?.let { Text(it, color = Palette.Muted, fontSize = 10.sp, modifier = Modifier.padding(start = 16.dp)) }
                }
                Text("Which node blocks which is not on the wire (no dependency edges), so a pending node is shown as queued.", color = Palette.Muted, fontSize = 10.sp)
            }
        }
        HorizontalDivider(color = Palette.Border)
        Text("Submit a pod", color = Palette.Muted, fontSize = 10.sp)
        OutlinedTextField(goal, { goal = it }, label = { Text("Goal") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            nodesText, { nodesText = it }, label = { Text("Nodes: name [after a,b] [x3]: prompt   (one per line)") },
            minLines = 3, maxLines = 8, modifier = Modifier.fillMaxWidth(), textStyle = TextStyle(fontFamily = Mono, fontSize = 12.sp),
        )
        error?.let { Text(it, color = Palette.Danger, fontSize = 11.sp) }
        Button(
            enabled = state.connection.isLive && goal.isNotBlank(),
            onClick = {
                parseNodes(nodesText).fold(
                    { nodes -> actionScope.launch { pods.submit(goal.trim(), nodes) }; error = null },
                    { error = it.message },
                )
            },
        ) { Text("Submit pod") }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(podId, { podId = it }, label = { Text("Load pod by id") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { actionScope.launch { pods.query(podId) } }, enabled = state.connection.isLive && podId.isNotBlank()) { Text("Load") }
        }
    }
}

private fun podColor(s: String) = when (s) {
    "RUNNING", "CANCELLING" -> Palette.Info
    "CONVERGED" -> Palette.Ok
    "FAILED" -> Palette.Danger
    else -> Palette.Muted
}

private fun nodeColor(s: String) = when (s) {
    "RUNNING" -> Palette.Info
    "SUCCEEDED" -> Palette.Ok
    "FAILED" -> Palette.Danger
    "PENDING" -> Palette.Accent
    else -> Palette.Muted
}
