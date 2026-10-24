# Chat markdown engine (CHAT-MD-ENGINE)

Assistant replies are formatted through a seam in `agenthqUiCore` (shared by desktop, Android and wasm; no JVM-only types):

- `ChatMarkdownEngine` (`ui/model/ChatMarkdownEngine.kt`): `parse(text, streaming): List<ChatBlock>` and `supports(feature)`. The chat calls only this. `BuiltInChatMarkdownEngine` (the hand-written parser) is the default.
- `ChatBlockRenderer` / `ChatBlockRenderers` (`ui/ChatBlockRenderers.kt`): block type -> composable. `DefaultChatBlockRenderer` draws every built-in block.
- `ChatMarkdown.engines` (registry) and `LocalChatMarkdown` (what the chat reads). The user picks the engine in Configuration > Appearance > "Markdown engine"; the id is a per-device pref (`chatMarkdownEngine`), unknown ids fall back to the built-in engine. No wire change.

Override points:

1. **Swap the whole engine**: implement `ChatMarkdownEngine` (for example over a multiplatform markdown library) and call `ChatMarkdown.engines.register(engine)` at startup; it then appears in the setting.
2. **Override one block type**: `renderers.override("table", ChatBlockRenderer { block -> ... })` (types: paragraph, code, list, heading, quote, table, rule, or a custom type name).
3. **Add a block type**: an engine emits `ChatBlock.Custom(type, text, data)`; register a renderer under `type`. A client with no renderer for it shows `text` as plain text, and an engine that throws is replaced by one plain paragraph, so neither can crash the chat.
