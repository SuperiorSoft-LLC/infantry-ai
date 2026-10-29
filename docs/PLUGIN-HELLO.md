# A config-driven block, end to end (Acme_Hello)

`plugins/community/Acme_Hello` is the example to copy. Its greeting block sits in the left panel, right under the
Board button, and prints a greeting. Its card in **Settings > Plugins** ("Hello greeting (Acme_Hello)") changes the
text while the app runs, with no restart:

| Field | Type | Effect on the block |
|---|---|---|
| Greeting | `radio`: Hello World / Hello Nation | the text printed |
| Custom message | `text` | appended: `Hello World, <message>` |
| Shout | `checkbox` | upper-cases it |
| Classes | `classes`, slot `text`, tokens `big` `bold` `muted` | size, weight, colour |

## 1. module.json

Declare the block as a panel section in the `left.board` slot, and give its id a config card. A community module owns
the config nodes of its own tabs and panel sections, so the card's `node` is the section's `id`.

```json
"panels": [
  { "id": "acme.hello.greeting", "panel": "left.board" }
],
"config": [
  { "node": "acme.hello.greeting", "group": "Hello greeting", "tab": "Plugins", "order": 900,
    "title": "Hello greeting (Acme_Hello)", "applies": "Applies to: the Acme_Hello block under the Board button, live.",
    "keywords": ["acme", "hello", "greeting", "example"],
    "fields": [
      { "key": "greeting", "type": "radio", "label": "Greeting", "default": "Hello World", "options": ["Hello World", "Hello Nation"], "order": 1 },
      { "key": "message", "type": "text", "label": "Custom message", "default": "", "order": 2 },
      { "key": "shout", "type": "checkbox", "label": "Shout", "default": false, "order": 3 },
      { "key": "classes", "type": "classes", "label": "Classes", "order": 4, "slots": { "text": { "tokens": ["big", "bold", "muted"] } } }
    ] }
]
```

`tab` picks the Settings tab. A label that is not one of the core tabs adds a tab of its own. The dialog draws the card
from these fields; the module draws no form.

## 2. The block (`HelloGreeting.kt`)

The block reads its node through `TabContext.config`, which is live, so a change in Settings recomposes it. The only
shell type it touches is `ConfigValues`.

```kotlin
@Composable
fun GreetingBlock(context: TabContext) {
    val config = context.config("acme.hello.greeting")
    val classes = config.classes("text").orEmpty()
    Text(
        greetingText(config.string("greeting"), config.string("message"), config.boolean("shout") == true),
        Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        style = greetingStyle(classes),   // big -> 18sp, bold -> Bold, muted -> 55% alpha
    )
}
```

## 3. Register it (`HelloPlugin.register`)

```kotlin
registrar.panel(PanelProvider(id = "acme.hello.greeting", panel = Panels.LEFT_BOARD, order = 10, testTag = "acme-hello-greeting-block") { GreetingBlock(it) })
```

The host refuses anything that is registered but not declared in module.json, or declared but not registered. Every
refusal is listed in Settings > Plugins.

## 4. Compile-time builds and the guard

Web and Android compile config in (D9). `AcmeHelloConfig.DECLARATIONS` is module.json's `config` written in Kotlin.
`AcmeHelloConfigTest` fails when the two differ, so change both together.

## Run it

```powershell
$env:AGENTHQ_STATE_DIR = "$env:TEMP\hello-state"; $env:AGENTHQ_PORT = "8812"; $env:AGENTHQ_PROVIDER = "stub"
.\gradlew.bat --no-parallel :superiorSoftLibrary:agentHqUi:runRealDaemon
```

`runRealDaemon` builds the community jars and loads them from the repo's `plugins/` folder (devTrust). It starts its
own daemon on the given state dir and port.

Tests: `:plugins:community:Acme_Hello:test` (the guard, the slot binding and the text rules) and
`:superiorSoftLibrary:agentHqUi:test --tests "*AcmeHelloGreetingUiTest"` (real window: the card switches World to
Nation, Shout, classes).
