// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The API package's own tests, and the tests of every module's web UI half (rust/plugins/*/*/ui/test), which import
// their sources the way the build helper resolves them.
import path from "node:path";
import { fileURLToPath } from "node:url";
import { defineConfig } from "vitest/config";
import { pluginAliases, pluginDependencies } from "./build/plugin-config.mjs";

const repo = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../../..");

export default defineConfig({
  plugins: [pluginDependencies()],
  resolve: { alias: pluginAliases() },
  test: {
    dir: repo,
    include: ["app/packages/ui-plugin-api/test/**/*.test.ts", "rust/plugins/*/*/ui/test/**/*.test.ts"],
    exclude: ["**/node_modules/**", "**/target/**", "**/dist/**"],
  },
});
