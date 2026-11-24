// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// HC-1 guard: the app reaches the platform only through the host seam (src/host.ts). `@tauri-apps` is imported in
// app/shell/src/host/tauri/ and nowhere else (the shell, the packages, the web UI plugins); the Tauri globals are read
// only in app/shell/src/host/. A new direct import fails here: put the call behind [Host] instead.
import { readdirSync, readFileSync, statSync, existsSync } from "node:fs";
import { join, relative } from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";

const app = fileURLToPath(new URL("../../..", import.meta.url));
const repo = join(app, "..");
const IMPORT = /(?:\bfrom\s*|\bimport\s*\(?\s*|\brequire\s*\(\s*)["']@tauri-apps\//;
const GLOBAL = /__TAURI(?:_INTERNALS)?__/;
// BOS-8: the OS-app host (host/battalion) is the second folder that may import @tauri-apps.
const IMPORT_ALLOWED = ["app/shell/src/host/tauri/", "app/shell/src/host/battalion/"];
const GLOBAL_ALLOWED = "app/shell/src/host/";

function sources(dir: string, out: string[] = []): string[] {
  if (!existsSync(dir)) return out;
  for (const name of readdirSync(dir)) {
    if (name === "node_modules" || name === "dist") continue;
    const p = join(dir, name);
    if (statSync(p).isDirectory()) sources(p, out);
    else if (/\.(ts|tsx|js|jsx|mts|mjs)$/.test(name)) out.push(p);
  }
  return out;
}

/** The app's sources: the shell, every package, and the web UI half of every plugin under rust/plugins. */
function roots(): string[] {
  const out = [join(app, "shell", "src")];
  for (const p of readdirSync(join(app, "packages"))) out.push(join(app, "packages", p, "src"));
  const plugins = join(repo, "rust", "plugins");
  for (const tier of existsSync(plugins) ? readdirSync(plugins) : []) {
    const t = join(plugins, tier);
    if (!statSync(t).isDirectory()) continue;
    for (const m of readdirSync(t)) out.push(join(t, m, "ui", "src"));
  }
  return out;
}

/** Each line of [text] that matches [re], outside line comments. */
function hits(text: string, re: RegExp): number[] {
  return text.split(/\r?\n/).flatMap((line, i) => (re.test(line.replace(/\/\/.*$/, "")) ? [i + 1] : []));
}

describe("platform calls go through the host seam", () => {
  it("the patterns catch every import form", () => {
    for (const s of ['import { invoke } from "@tauri-apps/api/core";', "const m = await import('@tauri-apps/plugin-dialog');", 'export { x } from "@tauri-apps/api";', 'import "@tauri-apps/api/core";']) expect(IMPORT.test(s), s).toBe(true);
    expect(IMPORT.test('import { host } from "@/host";')).toBe(false);
    expect(hits('// from "@tauri-apps/api"', IMPORT)).toEqual([]);
  });

  it("no file outside app/shell/src/host/tauri imports @tauri-apps, and only app/shell/src/host reads the Tauri globals", () => {
    const offenders: string[] = [];
    for (const root of roots()) {
      for (const file of sources(root)) {
        const rel = relative(repo, file).replace(/\\/g, "/");
        const text = readFileSync(file, "utf8");
        if (!IMPORT_ALLOWED.some((d) => rel.startsWith(d))) for (const line of hits(text, IMPORT)) offenders.push(`${rel}:${line} imports @tauri-apps`);
        if (!rel.startsWith(GLOBAL_ALLOWED)) for (const line of hits(text, GLOBAL)) offenders.push(`${rel}:${line} reads a Tauri global`);
      }
    }
    expect(offenders).toEqual([]);
  });

  it("scans the shell and the host folder (the guard is not looking at an empty tree)", () => {
    const files = sources(join(app, "shell", "src")).map((f) => relative(repo, f).replace(/\\/g, "/"));
    expect(files.some((f) => f.startsWith(IMPORT_ALLOWED[0]) ) && files.some((f) => f.startsWith(IMPORT_ALLOWED[1]))).toBe(true);
    expect(files.length).toBeGreaterThan(50);
  });
});
