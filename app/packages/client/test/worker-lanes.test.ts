// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
import { describe, expect, it } from "vitest";
import { NO_PROJECT_LANE, workerLanes } from "../src";

const projects = [
  { id: "p-b", name: "bravo" },
  { id: "p-a", name: "Alpha" },
  { id: "p-c", name: "Charlie" },
];
const w = (id: string, projectId: string | null) => ({ id, projectId });

describe("workerLanes", () => {
  it("holds exactly the working sessions, one lane per project", () => {
    const rows = [w("1", "p-a"), w("2", "p-b"), w("3", "p-a")];
    const lanes = workerLanes(rows, projects, null);
    expect(lanes.map((l) => l.projectId)).toEqual(["p-a", "p-b"]);
    expect(lanes.flatMap((l) => l.agents).length).toBe(rows.length);
    expect(lanes[0].agents.map((a) => a.id)).toEqual(["1", "3"]);
  });

  it("drops empty lanes: no working agent, no lane", () => {
    expect(workerLanes([], projects, "p-a")).toEqual([]);
    expect(workerLanes([w("1", "p-b")], projects, "p-a").map((l) => l.projectId)).toEqual(["p-b"]);
  });

  it("orders the active project first, then by name ignoring case, no-project last", () => {
    const rows = [w("1", null), w("2", "p-c"), w("3", "p-b"), w("4", "p-a")];
    expect(workerLanes(rows, projects, "p-c").map((l) => l.name)).toEqual(["Charlie", "Alpha", "bravo", NO_PROJECT_LANE]);
    expect(workerLanes(rows, projects, null).map((l) => l.name)).toEqual(["Alpha", "bravo", "Charlie", NO_PROJECT_LANE]);
  });

  it("puts sessions with no project in a lane at the end, and shows an unknown project by its id", () => {
    const lanes = workerLanes([w("1", undefined as unknown as null), w("2", "p-zzz")], projects, "p-a");
    expect(lanes.map((l) => [l.projectId, l.name])).toEqual([["p-zzz", "p-zzz"], [null, NO_PROJECT_LANE]]);
  });
});
