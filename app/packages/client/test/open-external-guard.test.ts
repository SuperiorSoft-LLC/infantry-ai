// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Rule 9 guard: a link that leaves the app goes through the host seam (`host.openExternal`). `window.open(` is written only in
// packages/client/src/host.ts, and `target="_blank"` only in the shell's ExternalLink component (inside the Tauri webview a raw
// `_blank` does nothing). The setup wizard stays open on a backdrop click and Esc, and the Setup entries reopen it.
import { existsSync, readdirSync, readFileSync, statSync } from "node:fs";
import { join, relative } from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";

const app = fileURLToPath(new URL("../../..", import.meta.url));
const repo = join(app, "..");

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

/** Each line outside comments that matches [re], except in the one [allowed] file. */
function offenders(re: RegExp, allowed: string): string[] {
  const out: string[] = [];
  for (const root of roots())
    for (const file of sources(root)) {
      const rel = relative(repo, file).replace(/\\/g, "/");
      if (rel === allowed) continue;
      readFileSync(file, "utf8")
        .split(/\r?\n/)
        .forEach((l, i) => !/^\s*(\/\/|\*|\/\*)/.test(l) && re.test(l.replace(/\s\/\/.*$/, "")) && out.push(`${rel}:${i + 1}`));
    }
  return out;
}

describe("links leave the app through one seam", () => {
  it("no raw window.open( outside the host seam", () => {
    expect(offenders(/\bwindow\.open\(/, "app/packages/client/src/host.ts")).toEqual([]);
  });
  it('no target="_blank" outside ExternalLink', () => {
    expect(offenders(/target=(["']|{["'`])_blank/, "app/shell/src/components/external-link.tsx")).toEqual([]);
  });
});

describe("the setup wizard cannot be lost", () => {
  const read = (p: string) => readFileSync(join(app, p), "utf8");
  it("ignores the dialog's own close requests (backdrop click, Esc): only Not now and Done close it", () => {
    const wizard = read("shell/src/components/setup/setup-wizard.tsx");
    expect(wizard).toContain("onOpenChange={() => {}}");
    expect(wizard).toContain("onClick={onClose}");
  });
  it("reopens by itself while needed, and the title bar and Settings both open it on demand", () => {
    expect(read("shell/src/components/setup/setup-wizard.tsx")).toContain('ui.set("dialog", "setup")');
    expect(read("shell/src/components/layout/title-bar.tsx")).toContain('ui.set("dialog", "setup")');
    expect(read("shell/src/components/dialogs/settings-body.tsx")).toContain('ui.set("dialog", "setup")');
  });
});
