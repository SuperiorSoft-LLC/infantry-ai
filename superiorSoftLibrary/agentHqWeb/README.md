# agentHqWeb

The AgentHQ browser client: agenthqUiCore compiled to Kotlin/WASM. It talks to a real daemon over the daemon's
WebSocket. An offline "Try the demo" mode runs on a built-in fake daemon instead.

Build the static bundle (no webpack):

    ./gradlew :superiorSoftLibrary:agentHqWeb:assembleWeb    # production, build/assembleWeb
    ./gradlew :superiorSoftLibrary:agentHqWeb:assembleDemo   # development (unminified), build/assembleDemo

The daemon serves this bundle itself at `http://host:port/` (same origin as its `/ws`). No other server is needed.
`src/wasmJsMain/resources/js-joda.esm.js` is a shipped copy of @js-joda/core 5.7.0 (BSD, see js-joda.LICENSE.txt).
kotlinx-datetime imports it.
