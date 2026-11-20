// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// RUST-UPD-2: the release pipeline's two small, locally testable steps (.github/workflows/rust-release.yml).
//
//   node release-tools.mjs plan <tauri.conf.json> <Cargo.toml> <latest-tag-or-""> <channel> <run-number>
//       Prints `key=value` lines for $GITHUB_OUTPUT: version, tag, bump (minor|patch|major|none), prerelease.
//       The version rule (owner): a feature bumps the MINOR, a bug fix bumps the PATCH. The tool cannot know which, so it
//       reports the kind it sees against the last release and refuses a version that did not move, that went back, or
//       that differs between tauri.conf.json and Cargo.toml. A nightly is `<next patch>-nightly.<run>`.
//
//   node release-tools.mjs feed <version> <notes-file> <base-url> <out> <platform=file[:sigfile]>...
//       Writes the Tauri updater `latest.json`. `file` is the asset's name under <base-url>; with a `:sigfile` the entry
//       carries that minisign signature (desktop). An `android-*` entry has no signature file: it carries the APK's
//       sha256 and size instead (read from the file next to the manifest), and an empty `signature` because Tauri's
//       desktop reader requires the field on every entry (src/feed.rs explains the phone side).
import { createHash } from "node:crypto";
import { readFileSync, writeFileSync, statSync } from "node:fs";
import { basename } from "node:path";

const SEMVER = /^(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.-]+))?$/;

export function parse(v) {
  const m = SEMVER.exec(String(v).trim().replace(/^v/, ""));
  if (!m) throw new Error(`not a semantic version: ${v}`);
  return { major: +m[1], minor: +m[2], patch: +m[3], pre: m[4] ?? null };
}

const core = (v) => [v.major, v.minor, v.patch];
export function compare(a, b) {
  for (let i = 0; i < 3; i++) if (core(a)[i] !== core(b)[i]) return core(a)[i] < core(b)[i] ? -1 : 1;
  return 0;
}

/** What kind of bump `next` is over `last`: major | minor | patch | none (equal) | back (older). */
export function bump(last, next) {
  const c = compare(parse(last), parse(next));
  if (c === 0) return "none";
  if (c > 0) return "back";
  const [l, n] = [parse(last), parse(next)];
  return n.major > l.major ? "major" : n.minor > l.minor ? "minor" : "patch";
}

export function cargoVersion(text) {
  const m = /^\[package\][^[]*?^version\s*=\s*"([^"]+)"/ms.exec(text);
  if (!m) throw new Error("Cargo.toml has no [package] version");
  return m[1];
}

/** The rust/ workspace version: the ONE version of the daemon and CLI. */
export function workspaceVersion(text) {
  const m = /^\[workspace\.package\][^[]*?^version\s*=\s*"([^"]+)"/ms.exec(text);
  if (!m) throw new Error("rust/Cargo.toml has no [workspace.package] version");
  return m[1];
}

export function plan({ confVersion, cargoVer, wsVer = confVersion, lastTag, channel, run }) {
  if (wsVer !== confVersion) throw new Error(`rust/Cargo.toml [workspace.package] is ${wsVer} but tauri.conf.json is ${confVersion}: the daemon and CLI would report the wrong version; bump both`);
  if (confVersion !== cargoVer) throw new Error(`tauri.conf.json is ${confVersion} but Cargo.toml is ${cargoVer}: bump both`);
  const last = lastTag ? lastTag.replace(/^rust-v/, "") : null;
  let version = confVersion;
  let kind = last ? bump(last, confVersion) : "first";
  if (kind === "back") throw new Error(`version ${confVersion} is older than the last release ${last}`);
  if (channel === "nightly") {
    // The next patch of the last release (or of this version when none), tagged as a prerelease.
    const base = parse(last ?? confVersion);
    version = `${base.major}.${base.minor}.${base.patch + (last ? 1 : 0)}-nightly.${run}`;
    kind = "nightly";
  } else if (kind === "none") {
    throw new Error(`version ${confVersion} is already released (${lastTag}): a release needs a version bump (features: minor, bugs: patch)`);
  }
  return { version, tag: `rust-v${version}`, bump: kind, prerelease: channel === "nightly" ? "true" : "false" };
}

export function feed({ version, notes, baseUrl, entries, readFile = (f) => readFileSync(f), sizeOf = (f) => statSync(f).size }) {
  const platforms = {};
  for (const e of entries) {
    const [platform, spec] = e.split("=");
    if (!platform || !spec) throw new Error(`bad entry ${e} (platform=file[:sigfile])`);
    const [file, sig] = spec.split(":");
    const entry = { signature: "", url: `${baseUrl.replace(/\/$/, "")}/${encodeURIComponent(basename(file))}` };
    if (platform.startsWith("android-")) {
      entry.sha256 = createHash("sha256").update(readFile(file)).digest("hex");
      entry.size = sizeOf(file);
    } else if (sig) {
      entry.signature = readFile(sig).toString().trim();
    } else {
      throw new Error(`${platform} needs a signature file (platform=file:sigfile)`);
    }
    platforms[platform] = entry;
  }
  if (!Object.keys(platforms).length) throw new Error("no platform entries");
  return { version, notes, pub_date: new Date().toISOString(), platforms };
}

function main(argv) {
  const [cmd, ...a] = argv;
  if (cmd === "plan") {
    const [conf, cargo, lastTag, channel, run, ws] = a;
    const p = plan({
      confVersion: JSON.parse(readFileSync(conf, "utf8")).version,
      cargoVer: cargoVersion(readFileSync(cargo, "utf8")),
      wsVer: ws ? workspaceVersion(readFileSync(ws, "utf8")) : undefined,
      lastTag,
      channel: channel || "stable",
      run: run || "0",
    });
    for (const [k, v] of Object.entries(p)) console.log(`${k}=${v}`);
  } else if (cmd === "feed") {
    const [version, notesFile, baseUrl, out, ...entries] = a;
    const f = feed({ version, notes: readFileSync(notesFile, "utf8").trim(), baseUrl, entries });
    writeFileSync(out, JSON.stringify(f, null, 2) + "\n");
    console.log(`wrote ${out}: ${Object.keys(f.platforms).join(", ")}`);
  } else {
    console.error("usage: release-tools.mjs plan|feed ...");
    process.exit(2);
  }
}

if (import.meta.url === `file:///${process.argv[1]?.replace(/\\/g, "/")}` || process.argv[1]?.endsWith("release-tools.mjs")) {
  try {
    main(process.argv.slice(2));
  } catch (e) {
    console.error(`release-tools: ${e.message}`);
    process.exit(1);
  }
}
