package com.acme.echo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.superiorsoftllc.agenthq.plugin.api.EndpointAccess
import com.superiorsoftllc.agenthq.plugin.api.EndpointReply
import com.superiorsoftllc.agenthq.plugin.api.Services
import com.superiorsoftllc.agenthq.ui.plugin.TabContainers
import com.superiorsoftllc.agenthq.ui.plugin.TabProvider
import com.superiorsoftllc.agenthq.ui.plugin.UiPlugin
import com.superiorsoftllc.agenthq.ui.plugin.UiRegistrar
import com.superiorsoftllc.agenthq.ui.plugin.circle
import com.superiorsoftllc.agenthq.ui.plugin.tabIcon
import kotlinx.coroutines.launch

/**
 * Example module with its own backend. It calls the endpoint `echo` by id (module.json says where it is); the host checks the
 * call, adds the bearer key from the secret `plugin.acme_echo` (field `apiKey`) and returns the reply. No key in this code.
 */
class EchoPlugin : UiPlugin {
    override fun register(registrar: UiRegistrar) {
        val endpoints = registrar.services.get(Services.ENDPOINTS) // null: not declared, or not allowed in modules.json
        registrar.tab(
            TabProvider(
                id = "acme.echo", container = TabContainers.CENTER, order = 96,
                title = { "Echo" },
                icon = tabIcon("acme-echo", { circle(12f, 12f, 9f) }),
                testTag = "center-tab-ACME_ECHO",
            ) { EchoTab(endpoints) },
        )
    }

    @Composable
    private fun EchoTab(endpoints: EndpointAccess?) {
        var text by remember { mutableStateOf("hello") }
        var reply by remember { mutableStateOf("") }
        val scope = rememberCoroutineScope()
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(text, { text = it }, label = { Text("Message") })
            OutlinedButton(onClick = {
                scope.launch { reply = if (endpoints == null) "the endpoints service is not allowed" else send(endpoints, text) }
            }) { Text("Send") }
            Text(reply, Modifier.testTag("acme-echo-reply"))
        }
    }

    companion object {
        suspend fun send(endpoints: EndpointAccess, text: String): String =
            when (val r = endpoints.call("echo", "POST", "/echo", text, mapOf("Content-Type" to "text/plain"))) {
                is EndpointReply.Ok -> "${r.status}: ${r.body}"
                is EndpointReply.Refused -> "refused: ${r.reason}"
                is EndpointReply.Failed -> "failed: ${r.reason}"
            }
    }
}
