// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// ARMORY-3: the Update button's store, by restart class: an app updates in place and its window reopens, a daemon-class
// update waits for the link to come back, a failure keeps the old version, and the shell never relaunches without a click.
import { describe, expect, it, vi } from "vitest";
import type { Envelope, ShellUpdater } from "@infantry/ui-plugin-api";
import { createArmoryStore, isBusy, parseUpdates, progressOf } from "../src/armory";

function link() {
  const sent: Envelope[] = [];
  const taps = new Set<(e: Envelope) => void>();
  return {
    sent,
    sendFrame: (e: Envelope) => (sent.push(e), true),
    onFrame: (l: (e: Envelope) => void) => (taps.add(l), () => void taps.delete(l)),
    push: (type: string, data: unknown = {}) => taps.forEach((t) => t({ type, data: JSON.stringify(data) })),
  };
}

const upd = (id: string, restart: string, kind = "app") => ({ id, name: id, kind, installed: "1.0.0", available: "1.1.0", notes: "n", restart });

function setup(restart: string, kind = "app", shell: ShellUpdater | null = null) {
  const l = link();
  const reload = vi.fn();
  const store = createArmoryStore(l, () => shell, reload);
  l.push("armory_updates", { updates: [upd("tower", restart, kind)] });
  return { l, reload, store };
}

describe("the Armory update store", () => {
  it("lists the updates the daemon reports and skips what is malformed", () => {
    const u = parseUpdates(JSON.stringify({ updates: [upd("a", "daemon"), { id: "" }, 3, upd("a", "none"), { id: "b" }] }));
    expect(u.map((x) => [x.id, x.restart])).toEqual([["a", "daemon"]]);
    expect(parseUpdates("nope")).toEqual([]);
  });

  it("none class: installs in the background and reopens the app's window, with no restart", () => {
    const { l, reload, store } = setup("none");
    store.getState().update("tower");
    expect(l.sent.at(-1)).toMatchObject({ type: "armory_update", data: JSON.stringify({ id: "tower" }) });
    l.push("armory_progress", { id: "tower", stage: "installing", percent: 60 });
    expect(progressOf(store.getState(), "tower")).toMatchObject({ phase: "updating", percent: 60 });
    expect(isBusy(progressOf(store.getState(), "tower"))).toBe(true);
    const before = store.getState().reopenKey;
    l.push("armory_progress", { id: "tower", stage: "done", percent: 100, action: "reopen", version: "1.1.0" });
    expect(progressOf(store.getState(), "tower")).toMatchObject({ phase: "done", version: "1.1.0" });
    expect(store.getState().reopenKey).toBe(before + 1);
    expect(reload).toHaveBeenCalled();
  });

  it("daemon class: shows reconnecting, and is done once the link is back", () => {
    const { l, reload, store } = setup("daemon");
    store.getState().update("tower");
    l.push("armory_progress", { id: "tower", stage: "done", percent: 100, action: "reconnect", version: "1.1.0" });
    expect(progressOf(store.getState(), "tower").phase).toBe("reconnecting");
    expect(reload).not.toHaveBeenCalled();
    l.push("ready");
    expect(progressOf(store.getState(), "tower").phase).toBe("done");
    expect(reload).toHaveBeenCalled();
  });

  it("a refused update (bad signature) shows the error and nothing reopens", () => {
    const { l, reload, store } = setup("none");
    store.getState().update("tower");
    const before = store.getState().reopenKey;
    l.push("armory_progress", { id: "tower", stage: "failed", error: "tower: refused, the package is unsigned" });
    expect(progressOf(store.getState(), "tower")).toMatchObject({ phase: "failed", error: "tower: refused, the package is unsigned" });
    expect(store.getState().reopenKey).toBe(before);
    expect(reload).not.toHaveBeenCalled();
    store.getState().update("tower"); // the user may try again
    expect(progressOf(store.getState(), "tower").phase).toBe("updating");
  });

  it("shell: downloads in the background, then relaunches only on a click", async () => {
    const shell: ShellUpdater = {
      check: vi.fn().mockResolvedValue({ available: true, version: "0.4.0", current: "0.3.0" }),
      download: vi.fn().mockResolvedValue("0.4.0"),
      relaunch: vi.fn().mockResolvedValue(undefined),
    };
    const { l, store } = setup("relaunch", "shell", shell);
    store.getState().update("tower");
    expect(progressOf(store.getState(), "tower").phase).toBe("downloading");
    await vi.waitFor(() => expect(progressOf(store.getState(), "tower").phase).toBe("ready"));
    expect(shell.download).toHaveBeenCalledTimes(1);
    expect(shell.relaunch).not.toHaveBeenCalled();
    expect(l.sent.some((f) => f.type === "armory_update")).toBe(false);
    store.getState().relaunch();
    expect(shell.relaunch).toHaveBeenCalledTimes(1);
  });

  it("shell: a feed with nothing newer, or a window with no updater, fails and leaves the app running", async () => {
    const none: ShellUpdater = { check: vi.fn().mockResolvedValue({ available: false, version: null, current: "0.3.0" }), download: vi.fn(), relaunch: vi.fn() };
    const a = setup("relaunch", "shell", none);
    a.store.getState().update("tower");
    await vi.waitFor(() => expect(progressOf(a.store.getState(), "tower").phase).toBe("failed"));
    expect(none.download).not.toHaveBeenCalled();
    const b = setup("relaunch", "shell", null);
    b.store.getState().update("tower");
    expect(progressOf(b.store.getState(), "tower")).toMatchObject({ phase: "failed", error: expect.stringContaining("cannot update") });
  });
});
