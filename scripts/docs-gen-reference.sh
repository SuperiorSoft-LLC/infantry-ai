#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) SuperiorSoft LLC
# Regenerates docs/wiki/reference/*.md from the code (CLI, MCP tools, protocol, settings, Lua API).
# The generator is rust/crates/infantry-guards/src/reference_docs.rs; the guard test reference_docs_are_fresh fails while a
# committed page differs from what the code renders. Usage: scripts/docs-gen-reference.sh [--check]
set -euo pipefail
cd "$(dirname "$0")/../rust"
if [ "${1:-}" = "--check" ]; then
  cargo test -p infantry-guards --test reference_docs
else
  INFANTRY_DOCS_WRITE=1 cargo test -p infantry-guards --test reference_docs
  git -C .. status --short -- docs/wiki/reference
fi
