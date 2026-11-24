// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// BOS-8: the OS-app host is picked only for an `app-<id>` window; standalone and the browser keep today's host; under the OS the
// shell updater (the update / Armory half) does not exist.
import { describe, expect, it } from "vitest";
import { browserHost } from "../src/host";
import { appIdOfLabel, endpointOf } from "../../../shell/src/host/battalion/os";
import { shellUpdater } from "../../../shell/src/plugins/shell-updater";

describe("host selection", () => {
  it("picks an OS app only for a window labelled app-<id>", () => {
    expect(appIdOfLabel("app-infantry-ai")).toBe("infantry-ai");
    for (const l of ["main", "login", "app-", "app-A", "app-x", "app-a/b", "", null, undefined]) expect(appIdOfLabel(l as string | null), String(l)).toBeNull();
  });
  it("standalone and a browser carry no os", () => {
    expect(browserHost().os ?? null).toBeNull();
  });
});

describe("endpoint from os_app_status", () => {
  it("turns the app's http url into its socket", () => {
    expect(endpointOf({ id: "infantry-ai", state: "running", url: "http://127.0.0.1:4242/" })).toMatchObject({ url: "ws://127.0.0.1:4242/ws", token: null, mode: "daemon" });
  });
  it("says why when the app is not running", () => {
    expect(() => endpointOf({ id: "a1", state: "stopped", url: null })).toThrow(/not running \(state: stopped\)/);
    expect(() => endpointOf({ id: "a1", state: "failed", url: null, lastError: "port busy" })).toThrow(/port busy/);
  });
});

describe("hidden update half", () => {
  const call = () => Promise.resolve(undefined as never);
  it("the desktop keeps its updater; inside the OS there is none", () => {
    expect(shellUpdater({ kind: "desktop", call, os: null })).not.toBeNull();
    expect(shellUpdater({ kind: "desktop", call })).not.toBeNull();
    expect(shellUpdater({ kind: "desktop", call, os: { appId: "infantry-ai", showDesktop: async () => {}, badge: async () => {} } })).toBeNull();
    expect(shellUpdater({ kind: "browser", call })).toBeNull();
  });
});
