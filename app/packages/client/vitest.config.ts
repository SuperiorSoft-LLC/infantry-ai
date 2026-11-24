// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// BOS-10c: a test that imports shell sources (host/battalion) needs the workspace name the shell's own vite config maps.
import path from "node:path";
import { defineConfig } from "vitest/config";

export default defineConfig({
  resolve: { alias: [{ find: /^@infantry\/client$/, replacement: path.resolve(__dirname, "src/index.ts") }] },
});
