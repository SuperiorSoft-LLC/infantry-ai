# Browser for agents (Infantry_Browser)

The official plugin `Infantry_Browser` gives an agent a real browser, and only when you grant it. It declares one MCP server named
`browser` (capability `browser`) that runs [Playwright MCP](https://github.com/microsoft/playwright-mcp):
`npx -y @playwright/mcp@0.0.85 --isolated --browser chromium`.

- **Walled by default.** The server is listed in MCP Tools (source `plugin`) but reaches no session without a grant that carries a
  purpose, for example a ticket that requires it: `mcp.require` with `{"capability":"browser","purpose":"..."}`. The browser is costly, so it is
  given for specific purposes only.
- **Isolated.** `--isolated` keeps the profile in memory: never the owner's Chrome profile, no cookies or logins kept between sessions.
  The owner's real Chrome is not this plugin.
- **Headed.** No `--headless` flag, so a window opens on the machine that runs the session. To run headless, add `--headless` to the
  server in MCP Tools (copy it as an Infantry server) or change `command` in the plugin's `module.json`.
- **Pinned.** The package version is in the command (`@0.0.85`); bump it there. `npx` fetches the package; the Chromium it drives is
  installed by Playwright's own `browser_install` tool on first use.
- **Runner-ready.** `localOnly` is false, so a remote runner whose image has Node gets it with the same grant.

Not available yet: the manifest has no placeholders for the state dir or a setting, so the profile path, headless and the version
are not live settings.
