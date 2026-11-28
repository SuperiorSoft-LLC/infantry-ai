# Plugin UI pages

A plugin's web UI half is an ES module that the daemon serves and the app loads at run time: a centre tab, a right-panel
tab, a Settings card, a card in the plugin's own settings, a badge on board cards, or a whole-window layout.

Truth: `app/packages/ui-plugin-api/src/contract.ts` and `index.ts` (the API),
`app/packages/ui-plugin-api/build/build-plugins.mjs` (the build), `rust/crates/infantry-daemon/src/compose/ui_bundles.rs`
(serving). Examples: `rust/plugins/official/Infantry_Triggers/ui` (a tab), `Infantry_Runners/ui` (a Settings card),
`Infantry_Desktop/ui` (a layout).

## Folder

```
Acme_Hello/
  module.json
  ui/src/index.tsx       # the entry
  ui/src/index.css       # optional
  ui/test/*.test.ts      # optional, vitest
```

## module.json

```json
{
  "name": "Acme_Hello",
  "tabs": [ { "id": "acme.hello", "container": "center" } ],
  "ui": {
    "apiVersion": 1,
    "entry": "ui/dist/index.js",
    "styles": ["ui/dist/index.css"],
    "contributes": [
      { "slot": "tab", "id": "acme.hello", "label": "Hello", "hint": "^0" }
    ]
  }
}
```

`ui.contributes[]` entries (`id`, `slot`, optional `label` and `hint`; `^N` in `hint` also binds Ctrl+N):

| `slot` | Where it shows | Props |
|---|---|---|
| `tab` | a centre tab (beside CLI, Mail, Board) | none |
| `panel` | a right-panel tab | none |
| `settings.card` | a card on the Settings page | none |
| `plugin.config` | a card in this plugin's own settings, under Plugins | `{ plugin }` |
| `card.badge` | a badge on every board ticket card | `{ ticket: {id, title, status, ...} }` |
| `chat.message.actions` | small buttons under each chat message (agent text, owner prompt, mail) | `{ sessionId, message: {id, kind, text, from?} }` |
| `layout` | a whole-window layout the user can pick; `"defaultLayout": true` makes it the default of a new device | `LayoutViewProps` (the shell is handed in as `Shell`) |

## The entry

```tsx
import { definePlugin, UI_API_VERSION } from "@infantry/ui-plugin-api";
import { Hand } from "lucide-react";

function HelloTab() {
  return <div>Hello from a plugin</div>;
}

export default definePlugin({
  apiVersion: UI_API_VERSION,
  activate(ctx) {
    ctx.register("acme.hello", { icon: Hand, render: () => <HelloTab /> });
  },
});
```

`activate(ctx)` receives:

- `ctx.id`, the module id, and `ctx.apiVersion`, the UI API the app provides;
- `ctx.link`, the window's daemon link: send a frame, listen to every frame (listeners are removed when the plugin is disabled);
- `ctx.register(id, view)`, which draws the contribution `id` in its slot;
- `ctx.onDispose(fn)`, run when the plugin is disabled or replaced by a new build (or return a function from `activate`).

`@infantry/ui-plugin-api` also exports `requester` and `json` (frame helpers), `useLinkState`, `usePreference`,
`useAgentActivity`, `AgentAvatar`, `windowFrame`, `shellUpdater`, `osApp`, `runnerRequest` and `openRunnerSetup`.
Import `react`, `react-dom` and `@infantry/ui` (the app's UI kit: `TabPage`, `Field`, `RefusalBanner`, `ago` and the
rest) as usual: the build turns those imports into the app's own copy, so a plugin looks like the app and ships no
second React. Reuse the kit's components; do not build your own dialog or card.

To talk to your daemon half, declare a frame in `daemon.frames` and send it on `ctx.link` (see
[Daemon tools](daemon-tools.md)).

## Build

From the repository root, with `pnpm install` done in `app/`:

```
node app/packages/ui-plugin-api/build/build-plugins.mjs <module dir> [<module dir>...]
```

It writes `<module>/ui/dist/` (Vite). `rust/plugins/stage.sh` runs it for every staged module that has
`ui/src/index.tsx`, then copies `ui/dist` beside the module's `module.json`; it refuses a module whose `ui` is set but
whose `ui/dist` was not built.

## Serving

The daemon serves an **enabled** official or core module's entry, styles and assets at
`GET /ui-plugins/<Module>/<path>`. A disabled module's UI leaves the app; a new build replaces the running one
(`onDispose` runs first).
