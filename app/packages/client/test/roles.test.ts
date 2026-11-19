// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
import { describe, expect, it } from "vitest";
import { MAX_ROLE_TRAITS, createRolesStore, roleList, type Envelope } from "../src";

function link() {
  const sent: Envelope[] = [];
  const taps = new Set<(e: Envelope) => void>();
  return {
    sent,
    sendFrame: (e: Envelope) => (sent.push(e), true),
    onFrame: (l: (e: Envelope) => void) => (taps.add(l), () => void taps.delete(l)),
    push: (e: Envelope) => taps.forEach((t) => t(e)),
  };
}
const role = (id: string, source = "BUILT_IN") => ({ id, name: id, color: "#f00", persona: "p", source, icon: "pm", description: "" });

describe("roles store", () => {
  it("loads roles, pins a model, orders traits and rolls a refused binding back", () => {
    const l = link();
    const s = createRolesStore(l);
    s.getState().ensureLoaded();
    expect(l.sent.map((e) => e.type)).toEqual(["role_list", "role_binding_list", "trait_list", "role_traits_list"]);
    l.push({ type: "roles", data: JSON.stringify([role("zed", "CUSTOM"), role("pm")]) });
    l.push({ type: "traits", data: JSON.stringify([{ id: "t1", name: "Terse", body: "b" }]) });
    expect(roleList(s.getState().roles).map((r) => r.id)).toEqual(["pm", "zed"]);

    s.getState().setBinding({ roleId: "pm", provider: "claude", model: "opus" });
    expect(s.getState().bindings.pm.model).toBe("opus");
    l.push({ type: "error", data: "role_binding_set refused: unknown model", requestId: l.sent[4].requestId });
    expect(s.getState().bindings.pm).toBeUndefined();

    s.getState().setRoleTraits("pm", ["t1"]);
    l.push({ type: "role_traits", data: JSON.stringify({ roleId: "pm", traitIds: ["t1"] }), requestId: l.sent[5].requestId });
    expect(s.getState().roleTraits.pm).toEqual(["t1"]);
    s.getState().setRoleTraits("pm", Array.from({ length: MAX_ROLE_TRAITS + 1 }, (_, i) => `t${i}`));
    expect(l.sent).toHaveLength(6);

    s.getState().deleteRole("zed");
    l.push({ type: "role_removed", data: "zed", requestId: l.sent[6].requestId });
    expect(s.getState().roles.zed).toBeUndefined();
  });
});
