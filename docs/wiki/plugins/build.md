# Build and load a plugin

This page builds the `Acme_Hello` plugin from [Create a plugin](create.md), loads it into a daemon with its own temp
data, and calls its tool. Every step below was run as written on Windows (Git Bash); the outputs are real.

## 1. Toolchain

```
rustup target add wasm32-wasip2
```

`cargo build --target wasm32-wasip2` produces a WebAssembly **component** directly; no other tool is needed.

## 2. Build the component

From the plugin folder:

```
cargo build --release --target wasm32-wasip2
```

The component is `target/wasm32-wasip2/release/acme_hello.wasm` (the crate name with `-` turned into `_`).

## 3. Lay out a plugins folder

```
<plugins>/official/Acme_Hello/module.json
<plugins>/official/Acme_Hello/daemon.component     # the .wasm, renamed to what daemon.component says
```

```
mkdir -p plugins/official/Acme_Hello
cp module.json plugins/official/Acme_Hello/
cp target/wasm32-wasip2/release/acme_hello.wasm plugins/official/Acme_Hello/daemon.component
```

Your own plugin goes under `community/` with the owner's grants in `modules.json`, or under `official/` in a plugins
folder you control ([Overview](overview.md)).

For the plugins inside the Infantry AI repository, `rust/plugins/stage.sh <out-dir> [release]` does steps 2 and 3 for
every module under `rust/plugins/official` (it also builds the web UI halves and copies `data` folders, and stages the
modules listed in `bundled-external.json` from the infantry-plugins repository).

## 4. Run a daemon on that folder

Use a daemon that does not touch your real data: its own state dir, a free port, loopback only.

```
export INFANTRY_PLUGINS_DIR=$PWD/plugins
export INFANTRY_ENV=dev                                   # see Endpoints; a release build is always prod
printf '%s' '<a token of 32 or more characters>' > token.txt
export INFANTRY_TOKEN_FILE=$PWD/token.txt
infantry-daemon --port 18977 --host 127.0.0.1 --state-dir $PWD/state
```

The daemon refuses a token shorter than 32 characters. In its log:

```
[plugin:Acme_Hello] Info hello plugin started
[plugin:Acme_Hello] Info [plugins] daemon half of Acme_Hello (official, wasm) loaded: 1 tool(s)
[infantry] composed 32 units (197 frame types), plugins: Acme_Hello
```

A refused module logs `[plugins] daemon half of <Id> refused: <why>` instead; see [Create](create.md) for the reasons.
`infantry health` lists the loaded plugins too.

## 5. Call the tool

`infantry mcp serve` is the MCP server agents use; any MCP client can call the tool through it. It reads the daemon
from `INFANTRY_URL` (a `ws://` address) and the token from `INFANTRY_TOKEN_FILE`:

```
export INFANTRY_URL=ws://127.0.0.1:18977/ws
printf '%s\n' \
 '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"t","version":"1"}}}' \
 '{"jsonrpc":"2.0","method":"notifications/initialized"}' \
 '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"acme_hello","arguments":{"name":"Ada"}}}' \
 | infantry mcp serve
```

Answer:

```
{"jsonrpc":"2.0","id":2,"result":{"content":[{"type":"text","text":"Hello, Ada! (env dev, api 0.1.0)"}],"isError":false}}
```

An `http://` value in `INFANTRY_URL` is refused (`the daemon URL must start with ws:// or wss://`).

## 6. Change and reload

Rebuild, copy the new `daemon.component` over the old one, and load the module again (installing or updating it from a
source, or restarting the daemon). Loading a module that is already loaded is a hot swap: the new version takes over
and the old one drains. Bump `version` so the swap shows in the log.

## Cleanup

Stop your test daemon by its process id (never by image name: the owner's app runs the same executable), then delete the
temp folders.
