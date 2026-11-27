// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
import { describe, expect, it } from "vitest";
import type { Envelope } from "../wire";
import { agoText, createHostStatsStore, hostStatsParts } from "./host-stats";

describe("hostStatsParts", () => {
  it("formats the old footer line", () => {
    const s = { freeMb: 10_854, totalMb: 32_000, agents: 2, agentsCpuPercent: 0.1 };
    expect(hostStatsParts(s, 1_000, 6_400).join(" · ")).toBe("last frame 5s ago · RAM 10.6 GB free · agents CPU 0.1%");
  });
  it("leaves out what the daemon has not sent", () => {
    expect(hostStatsParts(null, null, 0)).toEqual([]);
    expect(hostStatsParts({ freeMb: null, totalMb: null, agents: 0, agentsCpuPercent: null }, 100, 150)).toEqual(["last frame now"]);
  });
  it("ago text in s, m, h", () => {
    expect(agoText(59_000)).toBe("59s ago");
    expect(agoText(120_000)).toBe("2m ago");
    expect(agoText(7_200_000)).toBe("2h ago");
  });
});

describe("createHostStatsStore", () => {
  it("keeps the stats and stamps every frame", () => {
    let tap: (e: Envelope) => void = () => {};
    let t = 10;
    const store = createHostStatsStore({ sendFrame: () => true, onFrame: (l) => ((tap = l), () => {}) }, () => t);
    tap({ type: "host_stats", data: JSON.stringify({ freeMb: 2048, totalMb: 4096, agents: 1, agentsCpuPercent: 3.5 }) });
    expect(store.getState().stats?.freeMb).toBe(2048);
    t = 20;
    tap({ type: "mail", data: "{}" });
    expect(store.getState().lastFrameAt).toBe(20);
    expect(store.getState().stats?.agentsCpuPercent).toBe(3.5);
  });
});
