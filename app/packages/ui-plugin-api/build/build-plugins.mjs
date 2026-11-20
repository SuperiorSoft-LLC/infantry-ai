#!/usr/bin/env node
// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// WEBUI-1: builds web UI halves. `pnpm --filter @infantry/ui-plugin-api build-plugins [<module dir>...]` (from app/), or
// `node app/packages/ui-plugin-api/build/build-plugins.mjs [<module dir>...]`. No folder: every official and test plugin
// with a ui/src, and plugins/_template. rust/plugins/stage.sh runs it before it copies each module's ui files.
import { statSync } from "node:fs";
import path from "node:path";
import { build } from "vite";
import { moduleDirsWithUi, pluginBuildConfig, uiEntry } from "./plugin-config.mjs";

const args = process.argv.slice(2).map((d) => path.resolve(d));
const dirs = args.length ? args : moduleDirsWithUi();
let failed = 0;
for (const dir of dirs) {
  const name = path.basename(dir);
  if (!uiEntry(dir)) {
    console.log(`skip ${name}: no ui/src/index.tsx`);
    continue;
  }
  const t0 = performance.now();
  try {
    await build(pluginBuildConfig(dir));
    const size = (f) => {
      try {
        return `${statSync(path.join(dir, "ui", "dist", f)).size} B`;
      } catch {
        return "none";
      }
    };
    console.log(`built ${name} ui: index.js ${size("index.js")}, index.css ${size("index.css")} (${Math.round(performance.now() - t0)} ms)`);
  } catch (e) {
    failed += 1;
    console.error(`FAILED ${name} ui: ${e instanceof Error ? e.message : e}`);
  }
}
if (failed) process.exit(1);
