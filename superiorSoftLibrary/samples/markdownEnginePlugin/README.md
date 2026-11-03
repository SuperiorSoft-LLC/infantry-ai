# Write your own markdown engine in 10 minutes

This module is the example markdown engine plugin. It is built to one jar that the app loads at start-up, and it is the
file set to copy when you write your own.

## What a plugin is

A jar with:

1. a class implementing `com.superiorsoftllc.infantry.ui.model.ChatMarkdownEngine` (`id`, `label`, `parse(text, streaming)`,
   `supports(feature)`) and a public no-argument constructor;
2. `META-INF/services/com.superiorsoftllc.infantry.ui.model.ChatMarkdownEngine` naming that class (one per line).

It may use only the JDK, the Kotlin runtime and `com.superiorsoftllc.infantry.ui.model` (`ChatBlock`, `ChatSpan`,
`ColumnAlign`, ...). Nothing else of the app is visible to it, and it must not bundle those classes. No Compose: to
draw something new, return `ChatBlock.Custom(type, text, data)`; the app draws a block type it has a renderer for
(this example's `callout` is drawn by `CalloutBlockRenderer`), and shows `text` as plain text otherwise.

## Steps

1. Copy this folder, rename the package and the service file entry, change `id` and `label` (the id is what the
   setting stores; it must be unique and not `builtin`).
2. Edit `parse` in `CalloutMarkdownEngine.kt`. Return the blocks for the text; never throw (see below).
3. Build: `./gradlew :superiorSoftLibrary:samples:markdownEnginePlugin:pluginJar`
   gives `build/libs/markdown-engine-example.jar`.
4. Drop the jar in `<state dir>/markdown-engines/` (next to the voice `plugins/` folder) and restart the app.
5. Pick it in Configuration > Appearance > Markdown engine. Choose "Built-in" to switch back.

## Rules the host enforces

- `streaming` is true while a reply is still arriving: an unclosed fence, callout or table must degrade quietly (this
  example emits what has arrived so far). Today the chat always passes `false`, so this only matters once it streams.
- A plugin engine runs behind a guard: if `parse` throws (including `StackOverflowError`) or takes longer than
  250 ms, that message is shown with the built-in engine, one error line goes to stderr, and the engine is disabled
  until the next start. The built-in engine is never guarded.
- A jar that fails to load is reported and skipped; the others still load.

## Syntax of the example

`#`..`###` headings, `-` lists, ` ``` ` fences (literal), pipe tables, and

    :::callout warning
    Mind the gap.
    :::

## Test yours

Copy `ChatCorpusTest`'s idea: put message bodies as `.txt` files under
`infantryUiCore/src/commonTest/resources/chat-corpus/`; each is checked against the built-in and the example engine.
