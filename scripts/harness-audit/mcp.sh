#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) SuperiorSoft LLC
# HARNESS-AUDIT-1 probe: call ONE tool of the test daemon's MCP server over stdio.
# usage: STATE=<state> mcp.sh <dist> <tool> '<json args>'   (env INFANTRY_SESSION_ID etc. set the caller)
DIST="$1"; TOOL="$2"; ARGS="${3:-{\}}"
here="$(dirname "$0")"
{ printf '%s\n' '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"audit","version":"1"}}}'
  printf '%s\n' '{"jsonrpc":"2.0","method":"notifications/initialized"}'
  printf '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"%s","arguments":%s}}\n' "$TOOL" "$ARGS"
  sleep "${MCP_WAIT:-8}"; } | bash "$here/cli.sh" "$DIST" mcp serve ${MCP_PROJECT:+--project $MCP_PROJECT} 2>/dev/null | grep '"id":2'
