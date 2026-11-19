// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
import { describe, expect, it } from "vitest";
import { createLibraryStore, sortedByName, tagsOf, type Envelope } from "../src";

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

describe("library store", () => {
  it("loads skills, prompts and a layer; saves and rolls a refused delete back", () => {
    const l = link();
    const s = createLibraryStore(l);
    s.getState().ensureLoaded();
    expect(l.sent.map((e) => e.type)).toEqual(["skill_list", "prompt_list", "layer_skill_list"]);
    l.push({ type: "skills", data: JSON.stringify([{ id: "s1", name: "Review", description: "", body: "", tags: ["git"] }]) });
    l.push({ type: "prompts", data: JSON.stringify([{ id: "p1", name: "Fix", body: "fix it", tags: ["bug", "a"] }]) });
    expect(tagsOf(s.getState().prompts)).toEqual(["a", "bug"]);

    s.getState().savePrompt({ name: "Ask", body: "?", tags: [] });
    l.push({ type: "prompt_item", data: JSON.stringify({ id: "p2", name: "Ask", body: "?", tags: [] }), requestId: l.sent[3].requestId });
    expect(sortedByName(s.getState().prompts).map((p) => p.id)).toEqual(["p2", "p1"]);

    s.getState().deleteSkill("s1");
    expect(s.getState().skills.s1).toBeUndefined();
    l.push({ type: "error", data: "skill_delete refused: in use", requestId: l.sent[4].requestId });
    expect(s.getState().skills.s1).toBeDefined();

    s.getState().setLayer({ scope: "role", role: "pm" });
    expect(JSON.parse(l.sent[5].data)).toEqual({ scope: "role", role: "pm" });
    l.push({ type: "layer_skills", data: JSON.stringify({ scope: "role", projectId: null, role: "pm", skills: [{ scope: "role", name: "x", description: "", body: "", role: "pm" }] }) });
    expect(s.getState().layerSkills).toHaveLength(1);
  });
});
