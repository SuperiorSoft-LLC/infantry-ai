[← Wiki index](index.md)

# Web UI plugins

A plugin can ship a **web UI half**: an ES module the daemon serves next to the plugin's daemon half, which the app
loads at run time. It works the same on the desktop app, in the browser and on Android, with a local daemon or a
remote one. A new plugin appears without an app rebuild. Enabling or disabling it adds or removes its UI live, with no
reload.

The reference plugin is the Triggers tab: `rust/plugins/official/Infantry_Triggers/ui/`. It used to be part of
`app/shell`. Now its plugin draws it, and the tab looks the same.

## The manifest

`module.json` declares the half in a `ui` block (parsed in `rust/crates/infantry-daemon/src/compose/ui_bundles.rs`):

```json
"ui": {
  "apiVersion": 1,
  "entry": "ui/dist/index.js",
  "styles": ["ui/dist/index.css"],
  "assets": ["ui/dist/assets"],
  "contributes": [{ "slot": "tab", "id": "acme.board", "label": "Board", "hint": "^0", "order": 450 }]
}
```

| Field | Meaning |
|---|---|
| `apiVersion` | The UI API the half is built for. The app refuses any other version and says why (see Faults). |
| `entry` | The built ES module, a path inside the module folder. |
| `styles` | Optional. Built stylesheets, added after the app's own. |
| `assets` | Optional. Folders of other files the module loads (images, fonts), served under the same path. |
| `contributes` | Where the half draws: `slot`, `id` (the view `activate` registers), and optionally `label`, `hint` and `order`. |

The paths must stay inside the module folder. Only an **enabled** module's declared files are served:
`GET /ui-plugins/<Module>/<path>`, on the daemon's own port. The route is behind the access policy every other route
has (the bearer token, the loopback rule for a daemon with no token, no browser `Origin`). It answers with `nosniff`
and `no-cache`. The plugin list (`plugins_op list`) gives each module's `ui.web`: the API version, the entry and style
URLs, `contributes`, and a `rev` that changes when the files change, so the app reloads a rebuilt plugin by itself.

### Slots

| Slot | Where it draws | Props the view gets |
|---|---|---|
| `tab` | A centre tab, the whole page. | none |
| `panel` | A right-panel tab, drawn as a card. | none |
| `settings.card` | A card in the Settings dialog. Its `tab` field names the Settings tab, `Plugins` when it names none. Search matches its label, id and plugin. | none |
| `plugin.config` | A card in the plugin's own settings (the cog in Plugins), after its declared settings. | `{ plugin }` |
| `card.badge` | A badge on every ticket card, after the card's own chips. | `{ ticket }` (id, title, status...) |
| `layout` | A whole-window layout (DESK-2): drawn INSTEAD of the app's window when this device's `layout` preference is `<Plugin>/<id>`. The shell comes in as `Shell`, a placeholder it fills outside the plugin's style scope, so nothing inside changes. `View` draws another plugin's page. A failed layout falls back to the normal window. The reference is `Infantry_Desktop`. `Shell` also takes two optional nodes (DESK-4): `headerActions` sit at the right end of the shell's header, just left of the window controls, and `statusLeading` sits at the left of the bottom status line, which keeps its status text on the right. The header's minimize, maximize and close act on the real window. The real window's frame is `windowFrame()` from `@infantry/ui-plugin-api` (null in a browser or on a phone): the Desktop's Start menu uses it for "Minimize Infantry" and "Exit Infantry". The shell names no plugin. | `{ Shell, View }` |

`order` places a tab among the app's own tabs. The centre tabs are 100, 200 and so on up to 2000 (CLI 100 ... Settings
2000). Triggers is 600, where the built-in tab was. The right-panel tabs are 100 to 700. A contribution with no `order`
sorts after every one with one. `hint` is the key hint the tab bar shows, and `^<digit>` binds Ctrl+<digit>: Triggers
keeps `^0`. `label` is the tab's name.

## An example

`plugins/_template/ui/src/index.tsx`, the starter's centre tab:

```tsx
import { useState } from "react";
import { Rocket } from "lucide-react";
import { Button, PanelCard, TabPage } from "@infantry/ui";
import { definePlugin, UI_API_VERSION, useLinkState } from "@infantry/ui-plugin-api";
import "./index.css";

function Starter() {
  const link = useLinkState();
  const [n, setN] = useState(0);
  return (
    <TabPage title="Starter" description="A web UI half, drawn by the plugin.">
      <PanelCard title="Hello">
        <p className="text-sm text-muted-foreground">The daemon link is {link}.</p>
        <Button size="sm" onClick={() => setN(n + 1)}>Pressed {n} times</Button>
      </PanelCard>
    </TabPage>
  );
}

export default definePlugin({
  apiVersion: UI_API_VERSION,
  activate: (ctx) => ctx.register("example.starter.web", { icon: Rocket, render: () => <Starter /> }),
});
```

Its `index.css` is two lines: `@import "@infantry/ui-plugin-api/plugin.css";` and `@source "./";`. In `module.json`
it declares `"contributes": [{ "slot": "tab", "id": "example.starter.web", "label": "Starter", "order": 2100 }]`.

## Build and stage

From the repo root, after `pnpm install` in `app/`:

```
node app/packages/ui-plugin-api/build/build-plugins.mjs [<module dir>...]   # no folder: official, test and the template
rust/plugins/stage.sh <out-dir> [release]                                 # builds them too, then copies ui/dist
pwsh scripts/ui-live-preview.ps1 -StagePlugins                            # a live preview that loads them
```

The build writes `ui/dist/` (gitignored). `stage.sh` copies it to `<out>/official/<Module>/ui/dist/`, and the
installer ships it with the rest of the module. `INFANTRY_STAGE_TEST=1 stage.sh ...` (or the preview's
`-TestPlugins`) also stages the test plugins in `rust/plugins/test`. The type check of every web half is
`pnpm typecheck` in `app/` (`app/packages/ui-plugin-api/tsconfig.plugins.json`).

## The API: `@infantry/ui-plugin-api`

A plugin builds against this package (`app/packages/ui-plugin-api`). It does not bundle React or the UI kit: the app
lends its own copies. The build replaces `react`, `react/jsx-runtime`, `react-dom` and `@infantry/ui` with small shims
that read the app's copies from the host global, which the app installs before the first plugin loads. Anything else
a plugin imports (lucide-react icons, zustand, its own code) is bundled into its entry.

| Export | What it is |
|---|---|
| `definePlugin({ apiVersion, activate })` | The module's default export. `activate(ctx)` runs once per load. |
| `UI_API_VERSION` | The UI API this package describes (`1`). |
| `useLinkState()` | A hook: the window's daemon link state (`open` when connected). |
| `requester(link, prefix, onDown)`, `json(data)` | The app's frame helpers: send frames with request ids and settle their answers, and parse a frame's data. |
| `SLOTS`, `Contribution`, `SlotProps`, `View`, `UiPluginContext`, `UiPluginModule` | The contract types. |

`ctx` holds `id`, `apiVersion`, `link` (`sendFrame(env)` and `onFrame(listener)`), `register(id, view)` and
`onDispose(fn)`. A view is `{ render(props), icon? }`. The listeners a plugin adds to `link` are removed when it is
disabled, so a disabled plugin hears nothing. `onDispose`, or a function returned from `activate`, runs when the plugin
is disabled or replaced by a new build. The UI kit is `@infantry/ui` (`app/packages/ui`): the same components the app
draws with (TabPage, PanelCard, EmptyState, Chip, Button, Field, AppDialog...). The theme tokens come with it.

## How the app loads it

On connect, the app reads the plugin list. For each enabled module with a `ui.web` that it has not loaded, it reads
the entry and the styles from the daemon, imports the entry as a `blob:` module, and adds the styles as `<style>`
elements. Then it calls `activate`. The loader is `createUiPluginsStore` in
`app/packages/client/src/stores/ui-plugins.ts`, which knows no host. The window wires it in
`app/shell/src/plugins/web-plugins.tsx`.

| Host | How the files are read |
|---|---|
| Browser (the web shell) | `fetch` from the page's own origin. The dev server proxies `/ui-plugins` to the daemon, as it does `/ws`. |
| Desktop (Tauri) | The app process reads them: the `daemon_get` command (`app/src-tauri/src/daemon_http.rs`), a plain GET with the window's daemon URL and token. |
| Android (Tauri) | The same command. Its TLS is rustls with bundled web roots, so it needs no platform verifier. |
| Embedded runner (a desktop window with no daemon) | None: there is no daemon to serve them, and the loader says so. |

When the owner enables or disables a plugin, the daemon publishes `plugins_changed { id, enabled }` and the app reads
the list again. A newly enabled plugin loads. A disabled one is removed: its views unmount, `onDispose` runs, and its
styles and portal root go. A new `rev` loads the plugin again. Each load is timed: the User Timing measure
`ui-plugin <Module>` spans the first read to the end of `activate`, and DevTools' Performance panel shows it.

## Styles

- A plugin's stylesheet imports `@infantry/ui-plugin-api/plugin.css`: the app's theme tokens and Tailwind utilities,
  with no preflight. The build adds the utilities of the kit components the plugin imports.
- The build scopes every rule to `:where([data-ui-plugin="<Module>"])`, which adds no specificity. `:root` and `:host`
  (the theme variables) become that element. `@keyframes`, `@property` and `@font-face` are copied as they are, and
  declarations are never changed.
- The app draws each view inside `<div data-ui-plugin="<Module>" style="display: contents">`. The kit's dialogs,
  selects and menus portal into a root of the plugin's own at the end of `<body>`, which carries the same attribute
  (`PortalContainer` in `app/packages/ui/src/lib/portal.tsx`).
- Only the plugin's elements and kit components render inside a scope. The app's own UI, such as the fault card,
  renders outside it. The plugin's sheet comes after the app's, and its rules cannot reach the app.
- The kit's hand-written classes in `app/packages/ui/src/styles.css` are not built into a plugin's sheet. Use them
  through the kit's components.

## Faults

Each view renders inside an error boundary. A plugin that fails is replaced by a fault card in its own slot, and the
rest of the app keeps working. The plugin manager shows a `UI <phase> fault` chip on the plugin's row.

| Phase | When | Retry |
|---|---|---|
| `version` | `ui.apiVersion`, or the module's `apiVersion`, is not the app's. The card says: "<Module> needs UI API 2; this app provides UI API 1. Update the app to use it." | no |
| `fetch` | A file could not be read from the daemon. | yes |
| `import` | The module failed to evaluate, or exports no plugin. | yes |
| `activate` | `activate` threw, or a declared contribution got no view. | yes |
| `render` | A view threw while drawing. | yes |

On a ticket card a fault is a small red chip with the message in its tooltip. `rust/plugins/test/Acme_UiFault` is a
plugin whose tab throws, for proving it.

## Trust model

A web UI half runs in the app's page with the app's privileges. It can send any frame the window's link can, with the
owner's token. The error boundary isolates faults, not trust. So the daemon serves a web half only for a module it
loads: the official and core tiers today. A community module's web half is served only once the marketplace signs or
approves it (MARKET-1), the same as its daemon half.

The Tauri content security policy (`app/src-tauri/tauri.conf.json`) narrows what the page itself can load:

- `script-src 'self' blob:`. Scripts come from the app bundle, or from `blob:` modules the app made from files it read
  itself. There is no `'unsafe-eval'`, so neither the app nor a plugin can run `eval` or `new Function`. The app's zod
  runs jitless (`app/shell/src/zod-config.ts`) so that its eval probe raises no violation. A plugin that bundles a
  library with such a probe switches it off the same way (zod: `config({ jitless: true })`).
- `connect-src 'self' ipc: http://ipc.localhost`. The page connects to no daemon origin. The WebSocket and the plugin
  files go through the app process (tauri-plugin-websocket, `daemon_get`), and `daemon_get` reads plain
  `/ui-plugins/` paths only.
- `style-src 'self' 'unsafe-inline'`, for the kit's inline styles and the plugins' `<style>` sheets.
  `dangerousDisableAssetCspModification: ["style-src"]` stops Tauri from adding nonces, which would switch
  `'unsafe-inline'` off.
- `img-src` allows `http:` and `https:`, for avatars the daemon serves. `media-src` allows `data:`, for notification
  sounds. `object-src 'none'` and `base-uri 'self'` are also set. `devCsp` adds what the Vite dev server needs (its
  inline preamble and hot-reload socket).

## Footprint

Measured on 2026-11-20, when the Triggers tab moved out of the shell (WEBUI-1), against the shell before the move
(51fe8c170):

| `app/shell/dist` | Before | After | Change |
|---|---|---|---|
| `index-*.js` | 1,465,921 B (gzip 424.31 kB) | 1,485,474 B (gzip 430.75 kB) | +19,553 B (+1.3%) |
| `index-*.css` | 108,364 B | 108,364 B | none |
| All files | 2,266,922 B | 2,286,081 B | +19,159 B (+0.8%) |

The shell lost the Triggers tab and gained the loader, the host seam and the lent kit. `web-plugins.tsx` lends every
`@infantry/ui` export to plugins (`import * as Kit`), so tree-shaking keeps them all in the app.

A plugin's files are read only while it is enabled:

| Plugin | `index.js` | `index.css` |
|---|---|---|
| `Infantry_Triggers` | 25,598 B | 50,530 B |
| `plugins/_template` | 10,132 B | 24,387 B |
| `Acme_UiFault` (test) | 7,900 B | none |

A plugin's CSS holds the theme tokens and the utilities of the kit components it imports, all scoped to the plugin.

The load time is the `ui-plugin <Module>` measure, from the first read to the end of `activate`:

| Host | `Infantry_Triggers` | `Acme_UiFault` |
|---|---|---|
| Browser, Vite dev preview | 141 to 191 ms | 182 ms |
| Desktop debug build, connected to the daemon as a remote one (`daemon_get`) | 35 to 203 ms | 29 to 146 ms |

## Not yet

- Community modules: their web halves wait for MARKET-1.
- The embedded runner (a desktop window with no daemon) loads no web UI plugins.
- A browser page on another origin than the daemon cannot read the files, because the daemon refuses a browser
  `Origin` (CONTRACT §7). The web shell reads them through its own origin.
- One UI API version exists (`1`).
