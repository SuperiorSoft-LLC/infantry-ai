# Secrets: render a text file with your saved secrets

A secret you saved in the app (Configuration > Secrets, or a project's Secrets) can be written into ANY text file:
`.env`, `.txt`, `.json`, `.jsonc`, `.yaml`, `.toml`, `.ini`, `.conf`, a script. You write a **template** that names the
secret; the renderer makes a copy with the real value filled in. Nothing is tied to a format, a stack or a tool: the same
placeholder works everywhere, and no one (agent, script, log) ever sees the value.

## The placeholder

| Text in the template | Becomes |
|---|---|
| `{{secret:NAME}}` | the single value of secret `NAME` |
| `{{secret:NAME#FIELD}}` | one field of a secret that has several fields (`NAME` = `db`, `FIELD` = `PASS`) |
| `{{!secret:NAME}}` | itself, unchanged: the escape for a file that really contains `{{secret:` |

`NAME` is the secret's name as shown in Configuration (lowercase letters, digits, `.`, `_`, `-`). Resolution is the same as
for a saved command's secrets: the project's own secret first, then the app's. The syntax is one constant
(`SecretTemplate` in `plugins/official/Infantry_Run/daemon/src/main/kotlin/com/superiorsoftllc/infantry/plugins/official/run/daemon/SecretRender.kt`);
there is no per-format parsing, so quote the placeholder yourself where the format needs quotes.

Configuration shows each secret's placeholder next to it with a Copy button, and a **Render file** card (template, output, Render). The card sends the owner-only `secret_render` frame; an agent connection is refused, and the result shows names and the path only.

## Example: `.env`

Template `docker.env.tmpl`:

```
CMD_API_KEY={{secret:commandcode}}
DB_USER={{secret:db#USER}}
DB_PASS={{secret:db#PASS}}
```

```
infantry secret render docker.env.tmpl --out docker.env
filled: commandcode, db#USER, db#PASS
wrote: C:\work\docker.env
```

## Example: JSON with comments

Template `opencode.tmpl.jsonc`:

```
{
  // the provider key comes from the keystore
  "provider": { "commandcode": { "options": { "apiKey": "{{secret:commandcode}}" } } }
}
```

`infantry secret render opencode.tmpl.jsonc --out opencode.jsonc` writes the same file with the key inside the quotes.

## CLI

`infantry secret render <template> --out <file> [--project <id>] [--allow-missing]`
`infantry secret render <template> --check`

- The output is owner-only from its first byte (0600 on Unix, an owner-only ACL on Windows). The template is never
  changed, and the output may not be the template itself.
- stdout and stderr show NAMES only: what was filled, what is missing, and the file written. Never a value.
- A missing secret fails the whole render and writes nothing. `--allow-missing` leaves those placeholders in.
- `--check` reports the found and missing names and writes nothing.
- A binary file (a NUL byte, or not valid UTF-8) is refused with a plain message.
- A template is read up to the setting `limits.secrets.renderMaxBytes` (1 MiB by default).

## Agents and scripts

The MCP tool `infantry_secret_render` (Lua: `ih.call("infantry_secret_render", { template = "...", out = "..." })`) takes the same arguments
(`template`, `out`, `project`, `allowMissing`, `check`) and answers with names and the output path only. A relative path is
inside the project folder. An agent can render a file but cannot read a value: `infantry_run_secrets` still lists names and
scopes only.

## Saved commands

A saved command can list `renders`: `template=out` pairs, relative to the command's folder. They are rendered just before
each run starts (a failure refuses the start and names the secret) and the output files are deleted when the run ends, so a
credential file exists only while the command runs.

## Policy

A restricted or demo daemon refuses every render, like any other secret use.
