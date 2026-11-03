# Recipe: split a section of a plugin into a child plugin through a slot

Goal: a section that lives inside a host plugin's tab moves into its own plugin. The host keeps an empty named
place (a **slot**); the new plugin (the **child**) fills it. The host works without the child, and the Plugins tab
shows the child under the host.

The real example in the repository is the chat: `Infantry_Chat` draws the slot `chat.feed`, and `Infantry_ChatFeed`
fills it.

- Host: `plugins/official/Infantry_Chat/module.json` (the `slots` entry) and `plugins/official/Infantry_Chat/src/ChatPanel.kt`
  (draws the slot with `ChildSlot("chat.feed", ...)`).
- Child: `plugins/official/Infantry_ChatFeed/module.json` (`"panels": [{ "id": "infantry.chatfeed", "panel": "chat.feed" }]`)
  and `plugins/official/Infantry_ChatFeed/src/ChatFeedPlugin.kt`
  (registers a `PanelProvider` with `panel = "chat.feed"`).

## Files to touch

```
plugins/community/Acme_Notes/module.json                                   host: add "slots"
plugins/community/Acme_Notes/src/main/kotlin/com/acme/notes/NotesPlugin.kt host: draw the slot
plugins/community/Acme_NotesExtra/module.json                              child: new
plugins/community/Acme_NotesExtra/build.gradle.kts                         child: copy of the host's
plugins/community/Acme_NotesExtra/src/main/kotlin/com/acme/notesextra/NotesExtraPlugin.kt   child: new
```

A complete, built host and child (`Acme_Notes`, `Acme_NotesExtra`) are printed in
[Make a plugin](../make-a-plugin.md), section 11. This recipe is the same pattern as a sequence of edits.

## Steps

1. **In the host's `module.json`**, list the slot name. Use `<vendor>.<plugin>.<place>`:

```json
"slots": ["acme.notes.extra"]
```

2. **In the host's UI**, draw whatever fills the slot where the section used to be. A community host reads the
   sections from the tab factory (an official host uses `ChildSlot`, which needs a window controller that community
   code cannot reach):

```kotlin
import com.superiorsoftllc.agenthq.ui.LocalTabFactory

const val NOTES_SLOT = "acme.notes.extra"

// inside the tab's composable, where the section used to be:
LocalTabFactory.current.panels(NOTES_SLOT).forEach { section -> section.content(context) }
```

3. **Create the child.** Its `module.json` lists the host in `dependencies` (so it never loads alone) and a `panels`
   entry whose `panel` is the slot name:

```json
{
  "name": "Acme_NotesExtra",
  "version": "1.0.0",
  "apiVersion": 2,
  "author": "Acme",
  "license": "Apache-2.0",
  "description": "Child of Acme_Notes: fills its acme.notes.extra slot.",
  "dependencies": [{ "id": "Acme_Notes", "version": ">=1.0 <2" }],
  "entry": "com.acme.notesextra.NotesExtraPlugin",
  "jar": "build/libs/Acme_NotesExtra.jar",
  "panels": [{ "id": "acme.notesextra.hint", "panel": "acme.notes.extra" }]
}
```

4. **Move the section's code** into the child's `UiPlugin`. The child registers a `PanelProvider` whose `panel` is the
   slot name and whose `id` is the id declared above:

```kotlin
package com.acme.notesextra

import androidx.compose.material3.Text
import com.superiorsoftllc.agenthq.ui.plugin.PanelProvider
import com.superiorsoftllc.agenthq.ui.plugin.UiPlugin
import com.superiorsoftllc.agenthq.ui.plugin.UiRegistrar

class NotesExtraPlugin : UiPlugin {
    override fun register(registrar: UiRegistrar) {
        registrar.panel(PanelProvider(id = "acme.notesextra.hint", panel = "acme.notes.extra", order = 10, testTag = "acme-notes-hint") {
            Text("The section that used to live in the host.")
        })
    }
}
```

   `order` sorts the sections inside the slot. A panel that should take the full slot sets `fill = true`
   (`Infantry_ChatFeed` does).
5. **Copy the host's `build.gradle.kts`** into the child (same file; the jar name comes from `module.json`).
6. **Enable both** in `plugins/modules.json`. The child's `allow` lists only what it uses.

## Check

- `./gradlew :plugins:community:Acme_Notes:jar :plugins:community:Acme_NotesExtra:jar --console=plain` builds both.
- `PluginTree` (`superiorSoftLibrary/agenthqPlugins/src/commonMain/kotlin/com/superiorsoftllc/agenthq/plugin/host/PluginTree.kt`)
  makes a module a child of P when it lists P in `parents` or fills a slot P declares. The Plugins tab shows it under
  the host, and a child shared by two parents cannot be switched off while one of them is enabled.
- Add a test like the template's: `registerModule` the child, build a `TabFactory`, assert `factory.problems` is empty
  and `factory.panels("acme.notes.extra")` returns your section (see
  `plugins/_template/src/test/kotlin/com/example/starter/StarterPluginTest.kt` for the shape).

## Related

[UI plugin API](../plugin-api.md) section 3 (panel sections, slots), [Make a plugin](../make-a-plugin.md) section 6.
