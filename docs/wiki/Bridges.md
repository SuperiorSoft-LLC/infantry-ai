# Bridges: plugin capabilities and events

A plugin can offer **capabilities**: named functions with a params schema and a result schema. Any installed capability can be
called, under the same gates, by:

- a Lua script or hook (`ih.call("readaloud.speak", {text="Done"})`);
- an agent over MCP (`infantry_capability_call`);
- another plugin (`api.capabilities.call(...)` in a daemon half), always through the host, never by reaching into its classes.

A plugin can also declare the **events** it emits. They go on the daemon's named event bus, so a hook can use
`on = "<event>"`.

The bridge is one generic mechanism in core (PLUGIN-BRIDGES-1, DaemonApi 1.48.0). Core names no plugin and no capability.
Installing a plugin adds its capabilities. Disabling, removing or unloading it removes them.

## 1. Declaring capabilities and events (`module.json`)

```json
{
  "name": "Infantry_ReadAloud",
  "capabilities": [
    {
      "id": "readaloud.speak",
      "description": "Speak text aloud on the client.",
      "runs": "client",
      "params": { "type": "object", "properties": { "text": { "type": "string" }, "interrupt": { "type": "boolean" } }, "required": ["text"] },
      "result": { "type": "object", "properties": { "spoken": { "type": "boolean" } } },
      "action": "readaloud.speak",
      "kind": "write",
      "grant": "readaloud.speak",
      "timeoutMs": 10000
    }
  ],
  "emits": [
    { "event": "readaloud.spoken", "description": "A text was spoken.", "payload": { "type": "object" } }
  ]
}
```

| Field | Meaning |
|---|---|
| `id` | Dotted lower case, at least two segments (`vendor.name`). Unique across the installed plugins: the first module that declares an id keeps it, and a duplicate is refused and logged. |
| `runs` | `daemon`: the plugin's daemon half answers the call in-process. `client`: a connected client that loaded the plugin's UI half answers it. |
| `params`, `result` | JSON schemas, shown by `infantry_capability_list`. They are documentation for callers; the handler validates its own input. |
| `action`, `kind` | The policy action that gates a call. The default action is `capability.<id>`, and the default kind is `write`. A policy preset can allow or refuse it like any other action. |
| `grant` | Optional. A **community** plugin that calls this capability needs this grant in its `modules.json` `allow`. |
| `timeoutMs` | Optional. How long a call waits. It is capped at `limits.plugins.toolMaxTimeoutMs`. The default is `limits.plugins.capabilityTimeoutMs` (15 s). |

`emits[].event` is a full event name (dotted lower case, for example `readaloud.spoken`). The types are
`CapabilityDeclaration`, `CapabilityRuns` and `EmitDeclaration` in `infantryPlugins` ([UI plugin API](plugin-api.md)).

## 2. Answering a capability

**Daemon side.** Use `DaemonApi.capabilities` in `start` (see the [Daemon plugin API](daemon-plugin-api.md)):

```kotlin
override fun start(api: DaemonApi) {
    api.capabilities.handle("plugins.status") { call -> status(call.args) }   // returns a JsonElement; throw to fail
}
```

The handler runs under the plugin's guard and the capability's timeout. You can register only an id your own module.json
declares with `runs: daemon`, and each id takes one handler. Unloading the plugin removes the handler. The shipped example
is `plugins.status` in Infantry_Plugins: it returns one installed plugin's state, or `{installed: false}`.

**Client side.** Use `UiRegistrar.capability` in the UI half's `register`:

```kotlin
override fun register(registrar: UiRegistrar) {
    val speech = registrar.services.get(Services.SPEECH_OUT)
    registrar.capability("readaloud.speak") { args ->
        speech?.speak(args["text"]!!.jsonPrimitive.content, SpeakOptions(interrupt = true))
        buildJsonObject { put("spoken", speech != null) }
    }
}
```

The window tells the daemon what it hosts when its link goes live (`capability_host`), and again after every reconnect. A call
reaches it as a `capability_call` frame, and the answer goes back as a `capability_result` frame. Only an owner connection may
host. The UI half publishes a declared event with `registrar.capabilityEvents.emit("readaloud.spoken", payload)`. An event the
module does not declare is dropped.

## 3. Calling a capability

One generic tool serves every capability. That keeps the agent tool list short, because the list does not grow with the number
of plugins installed:

| Tool | What it does |
|---|---|
| `infantry_capability_list` | Every installed capability: id, module, schemas, where it runs, whether it can run now, and its client hosts with their ids. Policy action `capabilities.list`. |
| `infantry_capability_call {capability, args, target}` | Runs one capability. It is gated by the capability's declared action. |

`target` matters only for a client-side capability:

- omitted or `default`: the most recently active desktop host; if no desktop is connected, the most recently active host of any platform. "Active" means the last frame that client sent.
- `all`: every host. The result is an array of `{host, ok, result | error}`.
- a host id from `infantry_capability_list` (for example `desktop-1a2b3c4d`).

A call never hangs:

- With no host connected, it fails at once ("has no host").
- If the host does not answer, the call fails at the capability's timeout.
- If the host disconnects mid-call, the call fails at once.
- If the daemon half is not loaded, the call fails at once.

The answer is `{"ok": true, "host": "<host>", "result": ...}`, or an error with the reason.

**From Lua.** A dotted name passed to `ih.call` is a capability id. The script dispatcher runs it through
`infantry_capability_call` and puts the arguments under `args`. `_target` picks the client host, and `_optional` works as it
does for any action:

```lua
ih.call("readaloud.speak", {text = "Ticket done", _target = "all"})
```

**From another plugin.** Call it from the daemon half:

```kotlin
when (val r = api.capabilities.call("plugins.status", buildJsonObject { put("id", "Infantry_Notes") })) {
    is CapabilityResult.Ok -> r.value
    is CapabilityResult.Failed -> log(r.error)
}
```

## 4. Gates

Every caller passes the same gates:

| Caller | Gates |
|---|---|
| An agent, or the CLI | The capability's declared action under the active policy preset, plus the plugin frame gates. Both run in `infantry_capability_call`. |
| A script or hook | The same gates, because its call goes through the tool registry as the script's identity. |
| A plugin | The active preset's refusal of the declared action. A **community** caller also needs the capability's `grant`. A refusal is a `CapabilityResult.Failed` and is never thrown. |

## 5. Events

A daemon half publishes an event it declares with `api.events.publish("<event>", payload)`. A client sends `capability_event`;
the daemon accepts it only when that client hosts the module and the module declares the event. The event is published with
source `plugin:<module>`.

A hook binds the event by name:

```
infantry script hook add "readaloud.spoken" on-spoken
```

A client event carries no project, so under [project isolation](Scripting.md) it reaches only hooks marked `global`.

## 6. Example: speak when a ticket is done

The script (saved as `speak-done` with `infantry_script_save`):

```lua
-- speak-done.lua: read the finished ticket's title aloud on the most recently used desktop.
if not input.results then
  ih.call("readaloud.speak", {text = "Done: " .. (input.args.title or input.args.ticketId), _optional = true})
end
```

Bind it to the done lane:

```
infantry script hook add "lane enter done" speak-done
```

`_optional` keeps the hook from failing when no desktop with Read Aloud is connected; the run log records the "no host" error
instead.

## 7. Wire frames

The frames are defined in `CapabilityWire` (`infantryPlugins`), so the daemon and every client use the same types.

| Frame | Direction | Data |
|---|---|---|
| `capability_host` | client to daemon | `CapabilityHosting {platform, label, modules: [HostedModule]}`. Each one replaces the last; the answer is `{hostId}`. |
| `capability_call` | daemon to client | `CapabilityCallFrame {callId, capability, args}` |
| `capability_result` | client to daemon | `CapabilityResultFrame {callId, ok, result, error}` |
| `capability_event` | client to daemon | `CapabilityEventFrame {module, event, payload}` |
