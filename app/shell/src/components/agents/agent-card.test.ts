import { describe, expect, it } from "vitest";
import { cardStateClasses } from "./agent-card";

describe("PULSE-BORDER-1 agent card border", () => {
  it("pulses while working", () => expect(cardStateClasses(true, false)).toContain("border-pulse"));
  it("does not pulse while idle", () => expect(cardStateClasses(false, false)).not.toContain("border-pulse"));
  it("working and stale: red ring and pulsing border together", () => {
    const c = cardStateClasses(true, true);
    expect(c).toContain("border-pulse");
    expect(c).toContain("ring-danger/50");
  });
});
