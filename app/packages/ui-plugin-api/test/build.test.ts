// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
import { mkdtempSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { build } from "vite";
import { afterAll, describe, expect, it } from "vitest";
import { lentNames, moduleId, pluginBuildConfig, scopeCss, shimSource } from "../build/plugin-config.mjs";

const fixture = path.join(path.dirname(fileURLToPath(import.meta.url)), "fixtures", "Acme_Hello");
const out = mkdtempSync(path.join(tmpdir(), "infantry-ui-build-"));
afterAll(() => rmSync(out, { recursive: true, force: true }));

describe("the lent modules", () => {
  it("reads a lent module from the app's host global, named exports included", () => {
    const src = shimSource("react", ["useState", "default", "useEffect", "not-an-identifier"]);
    expect(src).toContain('globalThis["__INFANTRY_UI_HOST__"]');
    expect(src).toContain('host.modules["react"]');
    expect(src).toContain("export const { useEffect, useState } = m;");
    expect(src).toContain('export default m && "default" in m ? m.default : m;');
  });

  it("knows React's names from the installed copy and the kit's from the contract", () => {
    const names = lentNames();
    expect(names["react"]).toContain("useSyncExternalStore");
    expect(names["react/jsx-runtime"]).toEqual(expect.arrayContaining(["jsx", "jsxs", "Fragment"]));
    expect(names["@infantry/ui"]).toContain("Button");
  });
});

describe("the scoped stylesheet", () => {
  it("matches only inside the plugin's elements and keeps at-rules global", () => {
    const css = scopeCss("@layer theme{:root,:host{--c:red}}@layer utilities{.p-4{padding:1rem}.dark\\:x:is(.dark *){color:red}}@keyframes k{to{opacity:1}}", "Acme_X");
    expect(css).toContain('@layer theme{:where([data-ui-plugin="Acme_X"]){--c:red}}');
    expect(css).toContain(':where([data-ui-plugin="Acme_X"]) .p-4{padding:1rem}');
    expect(css).toContain(':where([data-ui-plugin="Acme_X"]) .dark\\:x:is(.dark *)');
    expect(css).toContain("@keyframes k{to{opacity:1}}");
  });
});

describe("a plugin build", () => {
  it("is one ES module with no imports and no React of its own, and a scoped stylesheet", async () => {
    expect(moduleId(fixture)).toBe("Acme_Hello");
    await build(pluginBuildConfig(fixture, { outDir: out }));
    const js = readFileSync(path.join(out, "index.js"), "utf8");
    const css = readFileSync(path.join(out, "index.css"), "utf8");
    expect(js).not.toMatch(/^\s*import\s/m);
    expect(js).not.toMatch(/\bfrom\s*["']/);
    expect(js).toContain("__INFANTRY_UI_HOST__");
    // React's own source never lands in a plugin: the reconciler and the hooks dispatcher come from the app.
    expect(js).not.toContain("react.production");
    expect(js).not.toContain("ReactCurrentDispatcher");
    expect(js.length).toBeLessThan(20_000);
    expect(css).toContain(':where([data-ui-plugin="Acme_Hello"]) .text-danger');
    // No preflight: the app has already laid down its base styles.
    expect(css).not.toContain("box-sizing:border-box");
  }, 60_000);
});
