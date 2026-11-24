import { describe, expect, it } from "vitest";
import { formatContextLimit, parseContextLimit } from "../src/context-limit";

describe("context limit text", () => {
  it("parses tokens, k, m and off; blank and junk are not a limit", () => {
    expect(parseContextLimit("500000")).toBe(500_000);
    expect(parseContextLimit("250k")).toBe(250_000);
    expect(parseContextLimit(" 1M ")).toBe(1_000_000);
    expect(parseContextLimit("1.5m")).toBe(1_500_000);
    expect(parseContextLimit("off")).toBe(0);
    expect(parseContextLimit("")).toBeNull();
    expect(parseContextLimit("lots")).toBeNull();
    expect(parseContextLimit("-5")).toBeNull();
  });
  it("accepts off or 10k to 10M and nothing else", () => {
    expect(parseContextLimit("10000")).toBe(10_000);
    expect(parseContextLimit("10k")).toBe(10_000);
    expect(parseContextLimit("10m")).toBe(10_000_000);
    expect(parseContextLimit("0")).toBe(0);
    expect(parseContextLimit("9999")).toBeNull();
    expect(parseContextLimit("5k")).toBeNull();
    expect(parseContextLimit("11m")).toBeNull();
  });
  it("formats what people read", () => {
    expect(formatContextLimit(500_000)).toBe("500k");
    expect(formatContextLimit(1_000_000)).toBe("1M");
    expect(formatContextLimit(0)).toBe("off");
    expect(formatContextLimit(null)).toBe("");
  });
});
