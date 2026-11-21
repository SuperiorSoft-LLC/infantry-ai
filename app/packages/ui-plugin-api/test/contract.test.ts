// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
import { describe, expect, it } from "vitest";
import * as Kit from "../../ui/src/index";
import host from "../src/host.json";
import type { HostApi, LayoutViewProps, ShellProps, ShellWindowControls, WindowFrame } from "../src/contract";
import { HOST_GLOBAL, HOST_MODULES, SCOPE_ATTRIBUTE, SLOTS, UI_API_VERSION, UI_EXPORTS } from "../src/contract";

describe("the UI API contract", () => {
  it("lends every runtime export of @infantry/ui, and nothing the kit does not export", () => {
    // A kit export added or removed changes what plugins can import: update src/ui-exports.json (and, for a removal,
    // UI_API_VERSION, since a plugin built against the old list would no longer load).
    expect([...UI_EXPORTS].sort()).toEqual(Object.keys(Kit).sort());
  });

  it("takes its constants from host.json, the file the build helper reads too", () => {
    expect(UI_API_VERSION).toBe(host.apiVersion);
    expect(HOST_GLOBAL).toBe(host.global);
    expect(SCOPE_ATTRIBUTE).toBe(host.scopeAttribute);
    expect([...HOST_MODULES]).toEqual(["react", "react/jsx-runtime", "react-dom", "@infantry/ui"]);
    expect([...SLOTS]).toEqual(["tab", "panel", "settings.card", "plugin.config", "card.badge", "layout"]);
  });

  it("lets a layout hand the shell header actions, status-line entries and its own window controls, all optional", () => {
    // Additive (DESK-4, DESK-5): a layout that passes none of them draws the shell as it always was.
    const none: ShellProps = {};
    const all: ShellProps = {
      className: "x",
      headerActions: "menu",
      statusLeading: "taskbar",
      windowControls: { minimize: () => undefined, toggleMaximize: () => undefined, close: () => undefined, maximized: true },
    };
    const shell: LayoutViewProps["Shell"] = () => null;
    expect(Object.keys(none)).toEqual([]);
    expect(Object.keys(all).sort()).toEqual(["className", "headerActions", "statusLeading", "windowControls"]);
    expect(Object.keys(all.windowControls!).sort()).toEqual(["close", "maximized", "minimize", "toggleMaximize"]);
    expect(shell).toBeTypeOf("function");
  });

  it("lends a plugin the real window's frame through HostApi.windowFrame (DESK-5: the Start menu)", () => {
    // `maximized` is optional: the real window's own frame (host.window) has no such notion and still satisfies the shape.
    const frame: WindowFrame = { minimize: () => undefined, toggleMaximize: () => undefined, close: () => undefined };
    const controls: ShellWindowControls = frame;
    const api: Pick<HostApi, "windowFrame"> = { windowFrame: () => null };
    expect(controls.maximized).toBeUndefined();
    expect(api.windowFrame()).toBeNull();
  });
});
