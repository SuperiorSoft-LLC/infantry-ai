// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Pins the pace colours (owner 11-19): ahead of pace (landing at or past the cap) is red/amber, under pace is green.
import { describe, expect, it } from "vitest";
import { meterTone } from "../../ui/src/blocks/meter-bar";

describe("meterTone", () => {
  it("under pace is green (ok)", () => {
    expect(meterTone(20, 60)).toBe("ok");
    expect(meterTone(50, 99)).toBe("ok");
    expect(meterTone(30)).toBe("ok");
  });
  it("ahead of pace is warn, far ahead is red (danger)", () => {
    expect(meterTone(40, 100)).toBe("warn");
    expect(meterTone(40, 110)).toBe("danger");
    expect(meterTone(40, 180)).toBe("danger");
  });
  it("high use alone escalates", () => {
    expect(meterTone(75, 0)).toBe("warn");
    expect(meterTone(90, 0)).toBe("danger");
  });
});
