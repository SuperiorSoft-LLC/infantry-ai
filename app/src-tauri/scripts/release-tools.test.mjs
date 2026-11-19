// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// RUST-UPD-2: node --test src-tauri/scripts
import test from "node:test";
import assert from "node:assert/strict";
import { bump, cargoVersion, feed, plan } from "./release-tools.mjs";

test("features bump minor, bugs bump patch, and a version that did not move or went back is refused", () => {
  assert.equal(bump("0.1.0", "0.2.0"), "minor");
  assert.equal(bump("0.1.4", "0.1.5"), "patch");
  assert.equal(bump("0.1.4", "1.0.0"), "major");
  assert.equal(bump("0.2.0", "0.1.9"), "back");
  const base = { cargoVer: "0.2.0", confVersion: "0.2.0", channel: "stable", run: "7" };
  assert.deepEqual(plan({ ...base, lastTag: "rust-v0.1.0" }), { version: "0.2.0", tag: "rust-v0.2.0", bump: "minor", prerelease: "false" });
  assert.throws(() => plan({ ...base, lastTag: "rust-v0.2.0" }), /needs a version bump/);
  assert.throws(() => plan({ ...base, lastTag: "rust-v0.3.0" }), /older/);
  assert.throws(() => plan({ ...base, cargoVer: "0.1.0", lastTag: "" }), /bump both/);
  assert.equal(plan({ ...base, lastTag: "" }).bump, "first");
});

test("a nightly is the next patch with a nightly suffix and is a prerelease", () => {
  const p = plan({ confVersion: "0.2.0", cargoVer: "0.2.0", lastTag: "rust-v0.2.0", channel: "nightly", run: "31" });
  assert.deepEqual(p, { version: "0.2.1-nightly.31", tag: "rust-v0.2.1-nightly.31", bump: "nightly", prerelease: "true" });
});

test("Cargo.toml's package version is read, not a dependency's", () => {
  assert.equal(cargoVersion('[package]\nname = "x"\nversion = "0.3.1"\n[dependencies]\nfoo = { version = "9" }\n'), "0.3.1");
});

test("the feed carries signatures for desktop and sha256 plus size for the APK", () => {
  const files = { "a.exe.sig": "SIGTEXT\n", "i.apk": Buffer.from("apk") };
  const f = feed({
    version: "0.2.0",
    notes: "n",
    baseUrl: "https://h/dl/",
    entries: ["windows-x86_64=a.exe:a.exe.sig", "android-aarch64=i.apk"],
    readFile: (n) => files[n],
    sizeOf: () => 3,
  });
  assert.equal(f.platforms["windows-x86_64"].signature, "SIGTEXT");
  assert.equal(f.platforms["windows-x86_64"].url, "https://h/dl/a.exe");
  assert.equal(f.platforms["android-aarch64"].size, 3);
  assert.match(f.platforms["android-aarch64"].sha256, /^[0-9a-f]{64}$/);
  assert.throws(() => feed({ version: "1.0.0", notes: "", baseUrl: "https://h", entries: ["windows-x86_64=a.exe"], readFile: () => "" }), /needs a signature/);
});
