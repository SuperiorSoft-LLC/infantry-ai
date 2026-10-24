import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.ComposeViewport
import com.superiorsoftllc.agenthq.ui.net.Controller
import com.superiorsoftllc.agenthq.ui.shell.AgentHqShell
import com.superiorsoftllc.agenthq.ui.shell.defaultPlatformHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    ComposeViewport { DemoRoot() }
}

@Composable
private fun DemoRoot() {
    var demo by remember { mutableStateOf(false) }
    if (!demo) {
        Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
            Text("AgentHQ")
            Button(onClick = { demo = true }) { Text("Try the demo") }
            Text("Offline demo with fake data. No server, no token.")
        }
    } else {
        val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
        val controller = remember { Controller(scope, FakeDaemonClient(scope)) }
        AgentHqShell(defaultPlatformHost(), controller)
    }
}
