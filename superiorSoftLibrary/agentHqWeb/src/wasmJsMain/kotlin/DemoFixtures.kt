import com.superiorsoftllc.agenthq.ui.protocol.Envelope

/** Hand-written, secret-free fixtures in the JSON shapes the real daemon sends. */
object DemoFixtures {
    const val firstId = "demo-architect"

    private fun session(id: String, role: String, state: String) =
        "{\"id\":\"$id\",\"provider\":\"demo\",\"workingDir\":\"/demo/project\",\"spawnedAt\":1,\"lastHeartbeat\":1," +
            "\"state\":\"$state\",\"role\":\"$role\",\"model\":\"demo-model\"}"

    val sessions = "[" + listOf(
        session(firstId, "architect", "IDLE"),
        session("demo-developer", "developer", "BUSY"),
        session("demo-qa", "qa", "WAITING_INPUT"),
    ).joinToString(",") + "]"

    val history: Map<String, List<Pair<String, String>>> = mapOf(
        firstId to listOf(
            Envelope.TURN to "Design a browser client for AgentHQ.",
            Envelope.TEXT_DELTA to "Run the UI in WebAssembly, keep the daemon headless, and let the page talk to it over a websocket. ",
        ),
        "demo-developer" to listOf(Envelope.TEXT_DELTA to "Working on the wasmJs target..."),
    )

    const val reply = "This is the offline demo: no daemon is running, so this reply is canned. Connect a real daemon to talk to a real agent."
}
