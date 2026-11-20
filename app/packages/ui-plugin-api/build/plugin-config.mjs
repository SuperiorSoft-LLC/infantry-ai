// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// WEBUI-1: the Vite config that builds one module's web UI half, <module>/ui/src/index.tsx, into the two files the
// daemon serves: <module>/ui/dist/index.js (one ES module with no imports left, so the app can import it from a blob)
// and <module>/ui/dist/index.css (the utilities its own sources and the kit components it draws use, built with the
// app's theme; no preflight).
//
// The app lends react, react/jsx-runtime, react-dom and @infantry/ui (src/host.json `modules`): the plugin's imports of
// them become reads of the app's own copy, so the bundle carries neither React nor the kit, and its components are the
// app's. Any other package it imports (lucide-react, zustand...) is bundled, resolved from this package's dependencies,
// so a module folder outside app/ builds with no node_modules of its own.
//
// The stylesheet is scoped: every rule is rewritten to match only inside an element carrying the plugin's
// `data-ui-plugin` attribute (the app sets it on each place it draws the plugin, and on the plugin's portal root), so a
// plugin's utilities never reorder or override the app's own.
import { existsSync, readdirSync, readFileSync, statSync } from "node:fs";
import { createRequire } from "node:module";
import path from "node:path";
import { fileURLToPath } from "node:url";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";

const here = path.dirname(fileURLToPath(import.meta.url));
export const apiDir = path.resolve(here, "..");
export const appDir = path.resolve(apiDir, "../..");
export const repoDir = path.resolve(appDir, "..");
const fromApi = createRequire(path.join(apiDir, "package.json"));
const readJson = (f) => JSON.parse(readFileSync(path.join(apiDir, "src", f), "utf8"));

/** src/host.json: the UI API version, the global the app installs, the scope attribute, the modules it lends. */
export const HOST = readJson("host.json");
/** src/ui-exports.json: the @infantry/ui exports the app lends. */
export const UI_EXPORTS = readJson("ui-exports.json");

/** The export names of each lent module: the kit's from the contract, React's from the installed copy (the app's). */
export function lentNames() {
  return Object.fromEntries(HOST.modules.map((m) => [m, m === "@infantry/ui" ? UI_EXPORTS : Object.keys(fromApi(m))]));
}

/** The module a lent import becomes: the app's copy, read from the host global when the plugin is imported. */
export function shimSource(mod, keys) {
  const named = [...new Set(keys)].filter((k) => k !== "default" && /^[A-Za-z_$][\w$]*$/.test(k)).sort();
  const missing = `${mod}: no Infantry AI app on this page (a web UI plugin runs inside the app)`;
  return [
    `const host = globalThis[${JSON.stringify(HOST.global)}];`,
    `if (!host) throw new Error(${JSON.stringify(missing)});`,
    `const m = host.modules[${JSON.stringify(mod)}];`,
    `export default m && "default" in m ? m.default : m;`,
    named.length ? `export const { ${named.join(", ")} } = m;` : "",
    "",
  ].join("\n");
}

const LENT = "\0infantry-lent:";

function lentModules(names) {
  return {
    name: "infantry-ui-lent-modules",
    enforce: "pre",
    resolveId: (id) => (Object.hasOwn(names, id) ? LENT + id : null),
    load: (id) => (id.startsWith(LENT) ? shimSource(id.slice(LENT.length), names[id.slice(LENT.length)]) : null),
  };
}

/**
 * A bare import from a module's own sources (outside app/) resolves as if from this package: its dependencies are what
 * plugins get. Also used by the tests of plugin sources (vitest.config.ts).
 */
export function pluginDependencies() {
  const anchor = path.join(apiDir, "package.json");
  return {
    name: "infantry-ui-plugin-dependencies",
    async resolveId(source, importer, options) {
      if (!importer || source.startsWith("\0") || source.startsWith(".") || path.isAbsolute(source) || /^[A-Za-z]:[\\/]/.test(source)) return null;
      if (path.resolve(importer).startsWith(appDir + path.sep)) return null;
      return this.resolve(source, anchor, { ...options, skipSelf: true });
    },
  };
}

/** The aliases a plugin build (and a plugin test) needs: the API package and the stylesheets Tailwind resolves. */
export function pluginAliases() {
  return [
    { find: /^@infantry\/ui-plugin-api$/, replacement: path.join(apiDir, "src", "index.ts") },
    { find: /^@infantry\/ui-plugin-api\/(theme|plugin)\.css$/, replacement: path.join(apiDir, "src", "$1.css") },
    // Tailwind resolves a stylesheet's @import through the aliases only (not through the resolve hooks above).
    { find: /^tailwindcss(\/.*)?$/, replacement: `${path.join(appDir, "node_modules", "tailwindcss")}$1` },
  ];
}

// The stylesheet scoper. It reads the built (minified) CSS as text: a style rule's selectors get the scope, a grouping
// at-rule (@layer, @media, @supports, @container...) is walked into, and the at-rules that hold no selectors (@keyframes,
// @property, @font-face...) are copied as they are. Declarations are never parsed, so no value can come out different.

/** At-rules copied whole: their blocks hold no selectors to scope. */
const VERBATIM = /^@(-[a-z]+-)?(keyframes|property|font-face|font-feature-values|font-palette-values|counter-style|page|view-transition|position-try)\b/i;
/** A selector that names the document root: it becomes the scope element itself (where the theme variables go). */
const ROOTISH = /^(:root|:host|html|body)(?![\w(-])/i;

/** Copies a comment or a quoted string at [i]; returns the index after it, or [i] when there is neither. */
function skipOpaque(css, i) {
  const c = css[i];
  if (c === "/" && css[i + 1] === "*") {
    const e = css.indexOf("*/", i + 2);
    return e < 0 ? css.length : e + 2;
  }
  if (c === '"' || c === "'") {
    for (let j = i + 1; j < css.length; j++) {
      if (css[j] === "\\") j++;
      else if (css[j] === c) return j + 1;
    }
    return css.length;
  }
  return i;
}

/** The index of the first [stops] character at bracket depth 0 from [i] (escapes, strings and comments skipped). */
function scanTo(css, i, stops) {
  let depth = 0;
  while (i < css.length) {
    const c = css[i];
    if (c === "\\") {
      i += 2;
      continue;
    }
    const j = skipOpaque(css, i);
    if (j !== i) {
      i = j;
      continue;
    }
    if (depth === 0 && stops.includes(c)) return i;
    if (c === "(" || c === "[") depth++;
    else if ((c === ")" || c === "]") && depth > 0) depth--;
    i++;
  }
  return css.length;
}

/** The index of the `}` that closes the block opened at [open]. */
function blockEnd(css, open) {
  let depth = 0;
  for (let i = open; i < css.length; i++) {
    const c = css[i];
    if (c === "\\") {
      i++;
      continue;
    }
    const j = skipOpaque(css, i);
    if (j !== i) {
      i = j - 1;
      continue;
    }
    if (c === "{") depth++;
    else if (c === "}" && --depth === 0) return i;
  }
  return css.length;
}

/** [list] (a selector list) with each selector inside [scope]; duplicates (`:root,:host`) kept once. */
function scopeSelectors(list, scope) {
  const out = [];
  let start = 0;
  const parts = [];
  for (let i = scanTo(list, 0, ","); ; i = scanTo(list, start, ",")) {
    parts.push(list.slice(start, i).trim());
    if (i >= list.length) break;
    start = i + 1;
  }
  for (const s of parts) {
    const m = ROOTISH.exec(s);
    const scoped = m ? scope + s.slice(m[0].length) : `${scope} ${s}`;
    if (s && !out.includes(scoped)) out.push(scoped);
  }
  return out.join(",");
}

function scopeRules(css, scope) {
  let out = "";
  let i = 0;
  while (i < css.length) {
    // Whitespace and comments between rules are copied (a /*! licence */ comment stays).
    const j = /\s/.test(css[i]) ? i + 1 : skipOpaque(css, i);
    if (j !== i) {
      out += css.slice(i, j);
      i = j;
      continue;
    }
    const end = scanTo(css, i, "{;}");
    if (end >= css.length || css[end] !== "{") {
      // A statement (`@layer a,b;`, `@import ...;`), or a stray `}`: copied.
      out += css.slice(i, Math.min(end + 1, css.length));
      i = end + 1;
      continue;
    }
    const prelude = css.slice(i, end).trim();
    const close = blockEnd(css, end);
    const body = css.slice(end + 1, close);
    if (!prelude.startsWith("@")) out += `${scopeSelectors(prelude, scope)}{${body}}`;
    else if (VERBATIM.test(prelude)) out += css.slice(i, close + 1);
    else out += `${prelude}{${scopeRules(body, scope)}}`;
    i = close + 1;
  }
  return out;
}

/**
 * [css] with every style rule matched only inside `[data-ui-plugin="<id>"]`: `.p-4` becomes
 * `:where([data-ui-plugin="<id>"]) .p-4` (`:where` adds no specificity, so the app's own state variants still win where
 * they do in the app), and `:root`/`:host` (the theme variables) become that element itself. @keyframes, @property and
 * @font-face stay as they are; declarations are not touched.
 */
export function scopeCss(css, id) {
  if (!/^[A-Za-z0-9_.-]+$/.test(id)) throw new Error(`"${id}" is not a module id`);
  return scopeRules(css, `:where([${HOST.scopeAttribute}="${id}"])`);
}

/** The scoping step: rewrites the bundle's stylesheet once Tailwind and the minifier are done with it. */
function scopeStyles(id) {
  return {
    name: "infantry-ui-scope-styles",
    enforce: "post",
    generateBundle(_, bundle) {
      for (const file of Object.values(bundle)) {
        if (file.type === "asset" && file.fileName.endsWith(".css")) file.source = scopeCss(String(file.source), id);
      }
    },
  };
}

// The kit files a plugin draws. Kit components render inside the plugin's scope, where its sheet comes after the app's:
// so its sheet must carry their classes too, or a plugin class would beat a kit class only the app's sheet has
// (src/plugin.css). Rather than every kit class, the build adds the kit files the plugin's sources import from
// `@infantry/ui`, and the kit files those import in turn, to the plugin stylesheet's sources.

const kitDir = path.join(appDir, "packages", "ui", "src");
const SOURCE = /\.(tsx?|jsx?|mts|mjs)$/;
const KIT = /["']@infantry\/ui["']/;

const isFile = (f) => existsSync(f) && statSync(f).isFile();

/** Every source file under [dir], outside node_modules and dist. */
function sourceFiles(dir) {
  if (!existsSync(dir)) return [];
  return readdirSync(dir, { withFileTypes: true }).flatMap((e) => {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) return e.name === "node_modules" || e.name === "dist" ? [] : sourceFiles(p);
    return SOURCE.test(e.name) ? [p] : [];
  });
}

/** The file a relative import [spec] of [from] names, or null. */
function resolveRelative(from, spec) {
  const base = path.resolve(path.dirname(from), spec);
  return [base, `${base}.tsx`, `${base}.ts`, path.join(base, "index.tsx"), path.join(base, "index.ts")].find((f) => SOURCE.test(f) && isFile(f)) ?? null;
}

/** The relative imports and re-exports of a source file. */
function relativeImports(src) {
  return [...src.matchAll(/(?:^|[;\s])(?:import|export)\s[^;]*?\bfrom\s*["'](\.[^"']*)["']|(?:^|[;\s])import\s*["'](\.[^"']*)["']/g)].map((m) => m[1] ?? m[2]);
}

/** The value names a kit file declares as exports. */
function declaredExports(src) {
  const names = [...src.matchAll(/export\s+(?:declare\s+)?(?:async\s+)?(?:function\*?|const|let|var|class)\s+([A-Za-z_$][\w$]*)/g)].map((m) => m[1]);
  for (const m of src.matchAll(/export\s*\{([^}]*)\}(?!\s*from)/g)) {
    for (const part of m[1].split(",")) {
      const [local, alias] = part.trim().split(/\s+as\s+/);
      if (local && !local.startsWith("type ")) names.push((alias ?? local).trim());
    }
  }
  return names;
}

/** Export name -> the kit file that declares it, from the kit's index (`export * from "./..."`). */
function kitExportFiles() {
  const index = path.join(kitDir, "index.ts");
  const byName = new Map();
  for (const spec of relativeImports(readFileSync(index, "utf8"))) {
    const file = resolveRelative(index, spec);
    if (file) for (const n of declaredExports(readFileSync(file, "utf8"))) if (!byName.has(n)) byName.set(n, file);
  }
  return byName;
}

/** The names a source imports (or re-exports) from `@infantry/ui` as values; null when it takes the whole kit. */
function kitImports(src) {
  if (/import\s*\(\s*["']@infantry\/ui["']\s*\)/.test(src) || /(?:import|export)\s*\*\s*(?:as\s+[\w$]+\s*)?from\s*["']@infantry\/ui["']/.test(src)) return null;
  const names = [];
  for (const m of src.matchAll(/(?:import|export)\s+(type\s+)?([^;]*?)\bfrom\s*(["'][^"']*["'])/g)) {
    if (m[1] || !KIT.test(m[3])) continue;
    const braces = /\{([^}]*)\}/.exec(m[2]);
    for (const part of braces ? braces[1].split(",") : []) {
      const p = part.trim();
      if (p && !p.startsWith("type ")) names.push(p.split(/\s+as\s+/)[0].trim());
    }
  }
  return names;
}

/**
 * The kit files [moduleDir]'s UI half can draw: those declaring what its sources import from `@infantry/ui`, and the kit
 * files they import, in turn. Null when a source takes the whole kit (`import * as`, a dynamic import): then all of it.
 */
export function kitSources(moduleDir) {
  const byName = kitExportFiles();
  const wanted = new Set();
  for (const file of sourceFiles(path.join(moduleDir, "ui", "src"))) {
    const names = kitImports(readFileSync(file, "utf8"));
    if (names === null) return null;
    for (const n of names) if (byName.has(n)) wanted.add(byName.get(n));
  }
  const seen = new Set();
  const stack = [...wanted];
  while (stack.length) {
    const file = stack.pop();
    if (seen.has(file)) continue;
    seen.add(file);
    for (const spec of relativeImports(readFileSync(file, "utf8"))) {
      const next = resolveRelative(file, spec);
      if (next && next.startsWith(kitDir + path.sep)) stack.push(next);
    }
  }
  return [...seen].sort();
}

/** The step that adds the kit files the plugin draws (or the whole kit) to the sources of its stylesheet. */
function kitClasses(moduleDir) {
  const files = kitSources(moduleDir) ?? [kitDir];
  return {
    name: "infantry-ui-kit-classes",
    enforce: "pre",
    transform(code, id) {
      const file = id.split("?")[0];
      if (!file.endsWith(".css") || !code.includes("@infantry/ui-plugin-api/plugin.css")) return null;
      const rel = (f) => path.relative(path.dirname(file), f).split(path.sep).join("/");
      const sources = files.map((f) => `@source "${rel(f)}";`).join("\n");
      return { code: `${code}\n/* The kit files this plugin draws (added by build/plugin-config.mjs). */\n${sources}\n`, map: null };
    },
  };
}

/** The entry of a module's UI half, or null when it has none to build. */
export function uiEntry(moduleDir) {
  return ["index.tsx", "index.ts", "index.jsx", "index.js"].map((f) => path.join(moduleDir, "ui", "src", f)).find((f) => existsSync(f)) ?? null;
}

/** The module id the app scopes the plugin's styles by: module.json `name`. */
export function moduleId(moduleDir) {
  const manifest = path.join(moduleDir, "module.json");
  const name = existsSync(manifest) ? JSON.parse(readFileSync(manifest, "utf8")).name : null;
  if (typeof name !== "string" || !name) throw new Error(`${moduleDir}: module.json has no name`);
  return name;
}

/**
 * The Vite inline config that builds [moduleDir]'s UI half into <moduleDir>/ui/dist (or [outDir]).
 * @param {string} moduleDir
 * @param {{ minify?: boolean, outDir?: string }} [options]
 * @returns {import("vite").InlineConfig}
 */
export function pluginBuildConfig(moduleDir, { minify = true, outDir } = {}) {
  const ui = path.join(moduleDir, "ui");
  const entry = uiEntry(moduleDir);
  if (!entry) throw new Error(`${moduleDir}: no ui/src/index.tsx to build`);
  return {
    configFile: false,
    root: ui,
    publicDir: false,
    logLevel: "warn",
    mode: "production",
    // A library build keeps process.env.NODE_ENV for its consumer; a plugin has no bundler after it.
    define: { "process.env.NODE_ENV": JSON.stringify("production") },
    plugins: [lentModules(lentNames()), pluginDependencies(), react(), kitClasses(moduleDir), tailwindcss(), scopeStyles(moduleId(moduleDir))],
    resolve: { alias: pluginAliases() },
    build: {
      outDir: outDir ?? path.join(ui, "dist"),
      emptyOutDir: true,
      target: "es2022",
      minify,
      sourcemap: false,
      cssCodeSplit: false,
      // One file: an imported image or font becomes a data URL (a module read from a blob has no folder beside it).
      assetsInlineLimit: () => true,
      lib: { entry, formats: ["es"], fileName: () => "index.js", cssFileName: "index" },
      rolldownOptions: { output: { codeSplitting: false } },
    },
  };
}

/** Every module folder with a UI half to build: the official and test plugins, and the template. */
export function moduleDirsWithUi() {
  const roots = [path.join(repoDir, "rust", "plugins", "official"), path.join(repoDir, "rust", "plugins", "test")];
  const dirs = [];
  for (const root of roots) {
    if (!existsSync(root)) continue;
    for (const name of readdirSync(root)) {
      const d = path.join(root, name);
      if (uiEntry(d)) dirs.push(d);
    }
  }
  const template = path.join(repoDir, "plugins", "_template");
  if (uiEntry(template)) dirs.push(template);
  return dirs;
}
