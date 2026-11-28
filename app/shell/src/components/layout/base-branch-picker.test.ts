// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// PARITY-AUDIT-1 #20: a confirmed pick sends one project_base; a cancel (or no change) sends nothing.
import { describe, expect, it } from "vitest";
import { createProjectsStore, type Envelope } from "@infantry/client";
import { CLEAR_BASE, stagedBase } from "./base-branch-picker";

function fakeStore() {
  const sent: Envelope[] = [];
  const store = createProjectsStore({ sendFrame: (e) => (sent.push(e), true), onFrame: () => () => {} });
  return { store, sent };
}

describe("base branch picker", () => {
  it("picking a branch and confirming sends the project_base update", () => {
    const { store, sent } = fakeStore();
    const next = stagedBase("main", "dev");
    expect(next).toBe("dev");
    store.getState().setBaseBranch("p1", next!);
    expect(sent).toEqual([{ type: "project_base", data: JSON.stringify({ projectId: "p1", branch: "dev" }) }]);
  });
  it("clear sends clear:true", () => {
    const { store, sent } = fakeStore();
    store.getState().setBaseBranch("p1", stagedBase("main", CLEAR_BASE)!);
    expect(sent).toEqual([{ type: "project_base", data: JSON.stringify({ projectId: "p1", clear: true }) }]);
  });
  it("cancel, or re-picking the current base, stages nothing to send", () => {
    expect(stagedBase("main", null)).toBeUndefined();
    expect(stagedBase("main", "main")).toBeUndefined();
    expect(stagedBase(undefined, CLEAR_BASE)).toBeUndefined();
  });
});
