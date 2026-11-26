# Test plugins

Plugins that exist to prove the plugin host, never shipped. `rust/plugins/stage.sh` stages them only when
`INFANTRY_STAGE_TEST=1` is set. It lays them out as official modules, because the daemon loads only the `official` and
`core` tiers. The live preview stages them with `pwsh scripts/ui-live-preview.ps1 -TestPlugins`.

| Plugin | What it proves |
|---|---|
| `Acme_Layouts` | PLUGIN-AGENTS-1: a plugin brings its own agent (`weather-agent`, module.json `roles`) and its own chat layout (`card:acme-weather`, a 5-day forecast drawn by its `chat.card` web view, fake data). Disabled or removed: the agent leaves New agent and old messages show the generic card with the `fallback` sentence. |
| `Acme_UiFault` | A web UI half whose centre tab throws while drawing. The app shows a fault card with Retry in that tab, and the rest of the app keeps working ([Web UI plugins](../../../docs/wiki/Web-UI-plugins.md)). |
