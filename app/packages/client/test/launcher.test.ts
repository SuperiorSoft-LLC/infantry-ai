// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
import { describe, expect, it } from "vitest";
import { EMPTY_DRAFT, addBlockedReason, pinOptions, rankRoles, unavailableLine, withDefaults, withRole, type HarnessInfo, type ModelGroup } from "../src";

const roles = ["pm", "frontend", "backend"].map((id) => ({ id, name: id, color: "#f00", persona: "", source: "BUILT_IN" as const, icon: "", description: "" }));
const harnesses: HarnessInfo[] = [{ id: "claude", default: true, defaultModel: "sonnet" }, { id: "pi" }];
const catalog: ModelGroup[] = [{ provider: "claude", group: "anthropic", label: "Anthropic", models: ["opus", "sonnet"] }];

describe("new agent draft", () => {
  it("ranks the role search and blocks Add until role, model and task are set", () => {
    expect(rankRoles(roles, "end").map((r) => r.id)).toEqual(["frontend", "backend"]);
    expect(rankRoles(roles, "pm")[0].id).toBe("pm");
    // A role is optional: the defaults alone (default harness and model) start an agent.
    const bare = withDefaults(EMPTY_DRAFT, harnesses, catalog);
    expect(bare.roleId).toBeNull();
    expect(bare.harness).not.toBeNull();
    expect(addBlockedReason(bare)).toBe("Type the agent's first task");
    expect(addBlockedReason({ ...bare, task: "Hello" })).toBeNull();
    const d = withRole(EMPTY_DRAFT, "pm", { roleId: "pm", provider: "claude", model: "opus" }, harnesses, catalog);
    expect(d).toMatchObject({ harness: "claude", model: "opus", group: "anthropic" });
    expect(addBlockedReason(d)).toBe("Type the agent's first task");
    expect(addBlockedReason({ ...d, task: "go" })).toBeNull();
    expect(withRole(EMPTY_DRAFT, "qa", undefined, harnesses, catalog).model).toBe("sonnet");
  });
});

describe("harness picker and role pin", () => {
  it("says why a greyed-out harness cannot run, and nothing when every one can", () => {
    expect(unavailableLine(harnesses)).toBeNull();
    const down: HarnessInfo[] = [
      { id: "claude", available: true },
      { id: "pi", available: false, unavailableReason: "pi is not installed (not on PATH)" },
      { id: "codex", available: false, unavailableReason: "  " },
    ];
    expect(unavailableLine(down)).toBe("pi: pi is not installed (not on PATH) · codex: cannot run on this machine");
  });

  it("offers a role pin every harness the daemon runs, metered or not, and its models", () => {
    const hs: HarnessInfo[] = [{ id: "claude" }, { id: "pi", models: ["anthropic/opus", "openai/gpt"] }, { id: "codex", models: ["gpt-5"] }];
    const cat: ModelGroup[] = [
      { provider: "pi", group: "anthropic", label: "anthropic", models: ["anthropic/opus"] },
      { provider: "pi", group: "openai", label: "openai", models: ["openai/gpt"] },
    ];
    const bound = [
      { roleId: "pm", provider: "claude", model: "opus" },
      { roleId: "qa", provider: "pi", model: "anthropic/sonnet" },
      { roleId: "x", provider: null, model: null },
    ];
    expect(pinOptions(hs, cat, bound, "").providers).toEqual(["claude", "codex", "pi"]);
    expect(pinOptions(hs, cat, bound, "").models).toEqual(["anthropic/sonnet", "opus"]);
    // a provider typed: the models its catalog groups list plus the ones already bound to it
    expect(pinOptions(hs, cat, bound, "pi").models).toEqual(["anthropic/opus", "anthropic/sonnet", "openai/gpt"]);
    // a harness without catalog groups offers its own list
    expect(pinOptions(hs, cat, bound, " codex ").models).toEqual(["gpt-5"]);
    expect(pinOptions([], [], [], "pi")).toEqual({ providers: [], models: [] });
  });
});
