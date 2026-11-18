// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
import { describe, expect, it } from "vitest";
import { bearerSubprotocol, decodeEnvelope, encodeEnvelope, isRunning, parseReady } from "../src";

describe("ready", () => {
  it("parses the key=value line the daemon sends", () => {
    const r = parseReady("provider=stub contract=1.155.0 instance=46c5d03d-3fac since=1794982434512 build=dev");
    expect(r).toEqual({ provider: "stub", contract: "1.155.0", instance: "46c5d03d-3fac", since: 1794982434512, build: "dev" });
  });
  it("treats a missing since as 0", () => {
    expect(parseReady("provider=stub").since).toBe(0);
  });
});

describe("envelope", () => {
  it("omits null requestId and origin like the Kotlin encoder", () => {
    expect(JSON.parse(encodeEnvelope({ type: "prompt", data: "hi", sessionId: "s1", requestId: undefined }))).toEqual({ type: "prompt", data: "hi", sessionId: "s1" });
  });
  it("ignores unknown keys and non-envelopes", () => {
    expect(decodeEnvelope('{"type":"ready","data":"x","novel":1}')?.type).toBe("ready");
    expect(decodeEnvelope("not json")).toBeNull();
    expect(decodeEnvelope('{"data":"x"}')).toBeNull();
  });
});

describe("bearer subprotocol", () => {
  it("is base64url without padding", () => {
    // "ab?>" -> base64 "YWI/Pg==" -> base64url "YWI_Pg"
    expect(bearerSubprotocol("ab?>")).toBe("infantry.bearer.YWI_Pg");
    expect(bearerSubprotocol("~~~")).toBe("infantry.bearer.fn5-");
  });
});

describe("running", () => {
  it("maps states to Running / Quiet", () => {
    expect(isRunning({ state: "BUSY" })).toBe(true);
    expect(isRunning({ state: "WAITING_INPUT" })).toBe(true);
    expect(isRunning({ state: "IDLE", turnActive: true })).toBe(true);
    expect(isRunning({ state: "IDLE" })).toBe(false);
    expect(isRunning({ state: "EXITED" })).toBe(false);
  });
});
