[← Wiki index](../index.md)

# Recipe: add an avatar style

Goal: a plugin that draws agent avatars in its own style, chosen by the `agentAvatarStyle` preference, with the built-in
portrait as the fallback. The registry, the request fields and the fallback rules are in
[UI plugin API, Agent avatar renderers](../plugin-api.md#agent-avatar-renderers-avatar-docs-1).

The starting point is `plugins/_template`, which builds with the rest of the repository. The example below calls the
style `mascot`; use your own key.

## Files to touch

```
plugins/community/Acme_Mascot/module.json
plugins/community/Acme_Mascot/build.gradle.kts
plugins/community/Acme_Mascot/src/main/kotlin/com/acme/mascot/MascotPlugin.kt
plugins/community/Acme_Mascot/src/main/kotlin/com/acme/mascot/MascotAvatarRenderer.kt
plugins/community/Acme_Mascot/src/test/kotlin/com/acme/mascot/MascotAvatarRendererTest.kt
plugins/modules.json            (existing file: enable and grant the module)
```

Nothing in `settings.gradle.kts` changes: it scans `plugins/community/*` for a `module.json`.

## Steps

1. Copy the starter and rename it. Delete `daemon/` in the copy: an avatar style is a UI-only plugin.
   In PowerShell: `Copy-Item -Recurse plugins/_template plugins/community/Acme_Mascot`, then remove the copied `daemon` folder.

2. Declare the implementation in `module.json`. The `implementations` list names the registry and the key your renderer
   registers under. The key is the style name the preference holds.

   ```json
   {
     "name": "Acme_Mascot",
     "version": "1.0.0",
     "apiVersion": 2,
     "author": "Acme",
     "description": "An avatar style: draws agents as a mascot.",
     "sequence": [],
     "entry": "com.acme.mascot.MascotPlugin",
     "jar": "build/libs/Acme_Mascot.jar",
     "implementations": [{ "registry": "agent.avatar.renderers", "key": "mascot" }]
   }
   ```

3. Write the renderer. It is a `fun interface AgentAvatarRenderer`, in the UI core package
   (`com.superiorsoftllc.infantry.ui`, from `infantryUiCore`). It must read only the `AvatarRequest` fields it needs and
   treat an unknown `state` string as idle.

   ```kotlin
   package com.acme.mascot

   import androidx.compose.foundation.background
   import androidx.compose.foundation.layout.size
   import androidx.compose.foundation.shape.CircleShape
   import androidx.compose.material.Text
   import androidx.compose.runtime.Composable
   import androidx.compose.ui.Modifier
   import androidx.compose.ui.draw.clip
   import androidx.compose.ui.platform.testTag
   import androidx.compose.ui.unit.dp
   import com.superiorsoftllc.infantry.ui.AgentAvatarRenderer
   import com.superiorsoftllc.infantry.ui.AvatarRequest

   /** Draws a plain coloured disc with the request's label; a real style draws its art here. */
   object MascotAvatarRenderer : AgentAvatarRenderer {
       const val TAG = "acme.mascot.avatar"

       @Composable
       override fun Draw(request: AvatarRequest, modifier: Modifier) {
           // Unknown or null states draw as idle: this style has no pose for them.
           val pose = when (request.state) {
               "BUSY" -> "working"
               "FAILED" -> "alert"
               else -> "idle"
           }
           Text(
               text = pose.take(1),
               modifier = modifier.size(request.sizeDp.dp).clip(CircleShape)
                   .background(androidx.compose.ui.graphics.Color.Gray)
                   .testTag(TAG),
           )
       }
   }
   ```

   Use `request.motion` to decide whether to animate: draw a still frame unless it is `AvatarMotion.LIVE`. A list row or
   a board card always gets `STATIC`.

   To read your own settings inside `Draw`, call `moduleConfig("<your node id>")` (package
   `com.superiorsoftllc.infantry.ui.plugin`). It returns `ConfigValues`, and a key that is not set reads null. Do not use
   `LocalShellContext`: a community jar cannot see `ui.shell`, so the renderer fails with a missing-class error. A community class
   loader shows only `AgentAvatarRenderer`, `AvatarRequest` and `AvatarMotion` from the `ui` package.

4. Register it in the plugin entry point. `UiPlugin.register` receives the `UiRegistrar`:

   ```kotlin
   class MascotPlugin : UiPlugin {
       override fun register(registrar: UiRegistrar) {
           registrar.implementation(AGENT_AVATAR_RENDERERS, "mascot", MascotAvatarRenderer)
       }
   }
   ```

   `AGENT_AVATAR_RENDERERS` is the constant `"agent.avatar.renderers"`, from the same UI core package.

5. Choose the style. The preference `agentAvatarStyle` (`AGENT_AVATAR_PREF_KEY`) is a per-device UI preference. Set it to
   `mascot`. There is no Appearance picker for it yet (ticket AVATAR-PICKER-1), so until that lands the value is set in
   the UI's preference store. Unset, or any key nothing registers, shows the built-in portrait.

6. Add a test. Copy the shape of `superiorSoftLibrary/infantryUi/src/test/kotlin/com/superiorsoftllc/infantry/ui/AgentPortraitUiTest.kt`:
   it uses `runDesktopComposeUiTest` and finds the avatar by its test tag. Cover at least: an unknown `state` draws the
   idle pose, and a renderer that throws shows the portrait instead.

## How to check it

- Build the plugin's UI jar the same way the starter does (see [Add a UI panel plugin](add-ui-panel-plugin.md)).
- Run the test class: `./gradlew :superiorSoftLibrary:infantryUi:test --tests "com.superiorsoftllc.infantry.ui.AgentPortraitUiTest" --console=plain`
  (replace the class with your own test once it exists).
- Run the app with the plugin enabled, set the preference to `mascot`, and open the sidebar and a chat. Every agent avatar
  should draw in the new style. Setting the preference back to `portrait` returns the built-in portrait. (Not yet
  verified: whether disabling the plugin alone, without changing the preference, takes effect without a restart.)
- A renderer that throws shows up in Settings > Plugins as a fault against your module, and the avatar draws as the portrait.

## Related

- [UI plugin API, Agent avatar renderers](../plugin-api.md#agent-avatar-renderers-avatar-docs-1): the request fields, the preference and the fallback.
- [Add a UI panel plugin](add-ui-panel-plugin.md): the starter steps this recipe builds on.
- [Agent avatars design record](../Agent-avatar-mascot-design.md): why the seam exists and the mascot plan built on it.
