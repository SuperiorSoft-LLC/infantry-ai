// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
import { describe, expect, it } from "vitest";
import { AUTO_ARCHIVE_KEY, autoArchiveKey } from "./archive-dialog";

describe("autoArchiveKey", () => {
  it("is the app key for all projects and <key>.<projectId> (what archive.rs reads) for one project", () => {
    expect(autoArchiveKey(null)).toBe(AUTO_ARCHIVE_KEY);
    expect(autoArchiveKey("project-1")).toBe("board.archive.doneAfterDays.project-1");
  });
});
