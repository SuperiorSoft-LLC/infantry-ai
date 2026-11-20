// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
import { describe, expect, it } from "vitest";
import { hostLabel, promptKey, shortcutHints, shortcutOf, stepThrough } from "../src";

const press = (key: string, mods: Partial<{ ctrlKey: boolean; metaKey: boolean; altKey: boolean; shiftKey: boolean }> = {}) => ({
  key,
  ctrlKey: false,
  metaKey: false,
  altKey: false,
  shiftKey: false,
  ...mods,
});

describe("shortcuts", () => {
  it("maps the Kotlin window keys; Cmd counts as Ctrl; plain keys do nothing", () => {
    expect(shortcutOf(press("K", { ctrlKey: true }))).toEqual({ kind: "search" });
    expect(shortcutOf(press("8", { metaKey: true }))).toEqual({ kind: "tab", digit: "8" });
    expect(shortcutOf(press("ArrowUp", { altKey: true }))).toEqual({ kind: "agent", step: -1 });
    expect(shortcutOf(press(",", { ctrlKey: true }))).toEqual({ kind: "settings" });
    expect(shortcutOf(press("k"))).toBeNull();
    expect(shortcutOf(press("k", { ctrlKey: true, shiftKey: true }))).toBeNull();
  });

  it("leaves Ctrl+R to the browser where Resume cannot run (UI-RESUME-WEB-1)", () => {
    expect(shortcutOf(press("r", { ctrlKey: true }), { resume: true })).toEqual({ kind: "resume" });
    expect(shortcutOf(press("r", { ctrlKey: true }), { resume: false })).toBeNull();
    expect(shortcutHints({ resume: true })).toContain("^R resume");
    expect(shortcutHints({ resume: false })).not.toContain("^R resume");
    expect(shortcutHints({ resume: false })).toContain("^K search");
  });

  it("steps through agents, wrapping", () => {
    expect(stepThrough(["a", "b", "c"], "c", 1)).toBe("a");
    expect(stepThrough(["a", "b", "c"], "a", -1)).toBe("c");
    expect(stepThrough(["a", "b"], "x", -1)).toBe("b");
    expect(stepThrough([], "x", 1)).toBeNull();
  });
});

describe("host label", () => {
  it("names a loopback daemon this computer, a remote one by host", () => {
    expect(hostLabel("ws://127.0.0.1:8797/ws")).toBe("This computer");
    expect(hostLabel("ws://100.67.51.8:18990/ws")).toBe("100.67.51.8");
  });
  it("reads Ctrl+Shift+N and Ctrl+Alt+K", () => {
    const k = (key: string, o: Partial<{ ctrlKey: boolean; altKey: boolean; shiftKey: boolean }>) =>
      shortcutOf({ key, ctrlKey: false, metaKey: false, altKey: false, shiftKey: false, ...o });
    expect(k("N", { ctrlKey: true, shiftKey: true })).toEqual({ kind: "switchAgent" });
    expect(k("k", { ctrlKey: true, altKey: true })).toEqual({ kind: "endSession" });
    expect(k("k", { altKey: true })).toBeNull();
  });
  it("maps prompt card keys", () => {
    expect(promptKey("ArrowRight", 4, 3)).toEqual({ kind: "move", at: 0 });
    expect(promptKey("ArrowUp", 4, 0)).toEqual({ kind: "move", at: 3 });
    expect(promptKey("2", 4, 0)).toEqual({ kind: "pick", at: 1 });
    expect(promptKey("5", 4, 0)).toBeNull();
    expect(promptKey("Enter", 4, 2)).toEqual({ kind: "pick", at: 2 });
    expect(promptKey("Enter", 3, 1, true)).toEqual({ kind: "submit" });
    expect(promptKey(" ", 3, 1, true)).toEqual({ kind: "toggle", at: 1 });
    expect(promptKey(" ", 3, 1)).toBeNull();
    expect(promptKey("Escape", 0, 0)).toEqual({ kind: "cancel" });
  });
});
