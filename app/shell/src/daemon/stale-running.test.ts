// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// CARD-STALE-1: the card timer turns red only while Running and past the threshold; 0 turns it off.
import { describe, expect, it } from "vitest";
import { staleRunningMinutes, STALE_RUNNING_DEFAULT } from "./agents";
import { staleMinutesOf } from "@/components/agents/use-stale-running";

const MIN = 60_000;
const now = 100 * MIN;

describe("staleRunningMinutes", () => {
  it("is red while Running and past the threshold", () => {
    expect(staleRunningMinutes("working", now - 34 * MIN, now, 30)).toBe(34);
    expect(staleRunningMinutes("working", now - 30 * MIN, now, 30)).toBe(30);
  });
  it("is not red under the threshold", () => {
    expect(staleRunningMinutes("working", now - 29 * MIN, now, 30)).toBeNull();
  });
  it("is never red when idle (also with background tasks), waiting or finished", () => {
    for (const s of ["idle", "waiting", "needs-input", "done", "error", "review"] as const)
      expect(staleRunningMinutes(s, now - 90 * MIN, now, 30)).toBeNull();
  });
  it("respects the threshold setting and 0 = off", () => {
    expect(staleRunningMinutes("working", now - 10 * MIN, now, 5)).toBe(10);
    expect(staleRunningMinutes("working", now - 90 * MIN, now, 0)).toBeNull();
  });
  it("reads the setting value with its default", () => {
    expect(staleMinutesOf(undefined)).toBe(STALE_RUNNING_DEFAULT);
    expect(staleMinutesOf(null)).toBe(30);
    expect(staleMinutesOf(0)).toBe(0);
    expect(staleMinutesOf("45")).toBe(45);
    expect(staleMinutesOf("x")).toBe(30);
  });
});
