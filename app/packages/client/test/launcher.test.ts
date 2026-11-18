// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
import { describe, expect, it } from "vitest";
import { EMPTY_DRAFT, addBlockedReason, rankRoles, withRole, type HarnessInfo, type ModelGroup } from "../src";

const roles = ["pm", "frontend", "backend"].map((id) => ({ id, name: id, color: "#f00", persona: "", source: "BUILT_IN" as const, icon: "", description: "" }));
const harnesses: HarnessInfo[] = [{ id: "claude", default: true, defaultModel: "sonnet" }, { id: "pi" }];
const catalog: ModelGroup[] = [{ provider: "claude", group: "anthropic", label: "Anthropic", models: ["opus", "sonnet"] }];

describe("new agent draft", () => {
  it("ranks the role search and blocks Add until role, model and task are set", () => {
    expect(rankRoles(roles, "end").map((r) => r.id)).toEqual(["frontend", "backend"]);
    expect(rankRoles(roles, "pm")[0].id).toBe("pm");
    expect(addBlockedReason(EMPTY_DRAFT)).toBe("Pick a role first");
    const d = withRole(EMPTY_DRAFT, "pm", { roleId: "pm", provider: "claude", model: "opus" }, harnesses, catalog);
    expect(d).toMatchObject({ harness: "claude", model: "opus", group: "anthropic" });
    expect(addBlockedReason(d)).toBe("Type the agent's first task");
    expect(addBlockedReason({ ...d, task: "go" })).toBeNull();
    expect(withRole(EMPTY_DRAFT, "qa", undefined, harnesses, catalog).model).toBe("sonnet");
  });
});
