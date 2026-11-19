// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
import { describe, expect, it } from "vitest";
import { inProject } from "../src";

const v = (id: string, projectId?: string | null) => ({ record: { id, projectId } });

describe("inProject", () => {
  const all = [v("a", "p1"), v("b", "p2"), v("c", "p1"), v("g", null)];
  it("keeps only the selected project's sessions (and global ones)", () => {
    expect(inProject(all, "p1").map((x) => x.record.id)).toEqual(["a", "c", "g"]);
    expect(inProject(all, "p2").map((x) => x.record.id)).toEqual(["b", "g"]);
  });
  it("shows everything when no project is selected", () => {
    expect(inProject(all, null)).toHaveLength(4);
  });
});
