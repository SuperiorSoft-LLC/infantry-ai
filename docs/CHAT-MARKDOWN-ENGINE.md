# Chat markdown engine (CHAT-MD-ENGINE)

Assistant replies are formatted through a seam in `agenthqUiCore` (shared by desktop, Android and wasm; no JVM-only types):

- `ChatMarkdownEngine` (`ui/model/ChatMarkdownEngine.kt`): `parse(text, streaming): List<ChatBlock>` and `supports(feature)`. The chat calls only this. `BuiltInChatMarkdownEngine` (the hand-written parser) is the default.
- `ChatBlockRenderer` / `ChatBlockRenderers` (`ui/ChatBlockRenderers.kt`): block type -> composable. `DefaultChatBlockRenderer` draws every built-in block.
- `ChatMarkdown.engines` (registry) and `LocalChatMarkdown` (what the chat reads). The user picks the engine in Configuration > Appearance > "Markdown engine"; the id is a per-device pref (`chatMarkdownEngine`), unknown ids fall back to the built-in engine. No wire change.

Override points:

1. **Swap the whole engine**: implement `ChatMarkdownEngine` (for example over a multiplatform markdown library) and call `ChatMarkdown.engines.register(engine)` at startup; it then appears in the setting.
2. **Override one block type**: `renderers.override("table", ChatBlockRenderer { block -> ... })` (types: paragraph, code, list, heading, quote, table, rule, or a custom type name).
3. **Add a block type**: an engine emits `ChatBlock.Custom(type, text, data)`; register a renderer under `type`. A client with no renderer for it shows `text` as plain text, and an engine that throws is replaced by one plain paragraph, so neither can crash the chat.

## Loadable engine plugins (MD-PLUGIN-1)

A jar in `<stateDir>/markdown-engines/` that declares a `ChatMarkdownEngine` through `META-INF/services` is loaded at start-up (`MarkdownEnginePluginLoader`, jvmMain; desktop only) in its own classloader that sees only the JDK, the Kotlin runtime and `ui.model`. It is registered like any engine and appears in the setting. A plugin engine runs behind `GuardedMarkdownEngine`: an exception, Error (not OutOfMemoryError) or a parse over 250 ms makes that message use the built-in engine, logs once, and disables the engine. Any engine that throws gets the built-in output for that message (`parseChatMarkdownSafely`). The `callout` block is drawn by the host (`CalloutBlockRenderer`). Worked example and README: `superiorSoftLibrary/samples/markdownEnginePlugin`. Parsing regression corpus: `agenthqUiCore/src/commonTest/resources/chat-corpus/`.
