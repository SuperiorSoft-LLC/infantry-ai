// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
import { describe, expect, it } from "vitest";
import { createProjectsStore, parseUnifiedDiff, splitRows, type Envelope } from "../src";

function link() {
  const sent: Envelope[] = [];
  const taps = new Set<(e: Envelope) => void>();
  return {
    sent,
    sendFrame: (e: Envelope) => (sent.push(e), true),
    onFrame: (l: (e: Envelope) => void) => (taps.add(l), () => taps.delete(l)),
    play: (type: string, body: unknown) => taps.forEach((t) => t({ type, data: typeof body === "string" ? body : JSON.stringify(body) } as Envelope)),
  };
}

describe("projects store", () => {
  it("selects the first project, reads its git view and keeps the patch of the open file only", () => {
    const l = link();
    const s = createProjectsStore(l);
    l.play("projects", [{ id: "p1", name: "One" }]);
    expect(s.getState().currentId).toBe("p1");
    expect(l.sent.map((e) => e.type)).toEqual(["project_git_status", "project_git_log", "project_git_branches", "project_git_worktrees"]);
    l.play("project_git", { projectId: "p1", directory: "/r", available: true, changes: [{ path: "a.rs", status: "MODIFIED", staged: false }] });
    expect(s.getState().status?.changes).toHaveLength(1);
    s.getState().openFile({ kind: "tree", path: "a.rs", staged: false });
    expect(JSON.parse(l.sent.at(-1)!.data)).toEqual({ projectId: "p1", path: "a.rs", staged: false });
    l.play("project_git_patch", { projectId: "p1", path: "b.rs", staged: false, available: true, patch: "x" });
    expect(s.getState().patch).toBeNull();
    l.play("project_git_patch", { projectId: "p1", path: "a.rs", staged: false, available: true, patch: "@@ -1 +1 @@\n-a\n+b\n" });
    expect(s.getState().patch?.patch).toContain("+b");
    l.play("error", "project_git_diff refused: path must not leave the repository: '../x'");
    expect(s.getState().error).toMatch(/refused/);
  });
});

describe("diff model", () => {
  it("numbers lines and pairs deletions with additions", () => {
    const lines = parseUnifiedDiff("diff --git a/x b/x\n--- a/x\n+++ b/x\n@@ -1,3 +1,3 @@\n keep\n-old\n+new\n tail\n");
    expect(lines.filter((l) => l.kind === "meta")).toHaveLength(3);
    const add = lines.find((l) => l.kind === "add")!;
    expect([add.text, add.newNo]).toEqual(["new", 2]);
    const rows = splitRows(lines);
    const pair = rows.find((r) => r.left?.kind === "del")!;
    expect(pair.right?.text).toBe("new");
  });
});

describe("project icons, edit and delete (PROJ-MENU-1)", () => {
  it("initials: 1-2 letters of the name", async () => {
    const { projectInitials } = await import("../src");
    expect(projectInitials("Infantry AI Site")).toBe("IS");
    expect(projectInitials("t51")).toBe("T5");
    expect(projectInitials("x")).toBe("X");
    expect(projectInitials("  ")).toBe("?");
  });
  it("icon: stored color wins, else a stable palette color; image only as a data URL", async () => {
    const { projectIcon, paletteColorFor, ICON_PALETTE } = await import("../src");
    expect(projectIcon({ id: "p1", name: "Alpha", data: { "icon.color": "#123456" } }).color).toBe("#123456");
    const c = projectIcon({ id: "project-abc", name: "Alpha" }).color;
    expect(ICON_PALETTE).toContain(c);
    expect(c).toBe(paletteColorFor("project-abc"));
    expect(projectIcon({ id: "p", name: "A", data: { "icon.image": "http://x" } }).image).toBeNull();
    expect(projectIcon({ id: "p", name: "A", data: { "icon.text": "Zz" } }).text).toBe("Zz");
  });
  it("delete needs exactly DELETE", async () => {
    const { deleteConfirmed } = await import("../src");
    expect(deleteConfirmed("DELETE")).toBe(true);
    expect(deleteConfirmed("delete")).toBe(false);
    expect(deleteConfirmed("DELETE ")).toBe(false);
  });
  it("edit sends project_save for the name and project_config for icon/settings, only what changed", () => {
    const l = link();
    const s = createProjectsStore(l);
    l.play("projects", [{ id: "p1", name: "Alpha", data: { "icon.color": "#2563eb", "icon.text": "AL" }, autoRemoveDoneWorkers: false }]);
    l.sent.length = 0;
    s.getState().editProject("p1", { name: "Beta", iconText: "", iconColor: "#16a34a", iconImage: null, autoRemoveDoneWorkers: true });
    expect(l.sent.map((e) => e.type)).toEqual(["project_save", "project_config"]);
    expect(JSON.parse(l.sent[0].data)).toEqual({ id: "p1", name: "Beta" });
    expect(JSON.parse(l.sent[1].data)).toEqual({ projectId: "p1", data: { "icon.color": "#16a34a" }, unsetData: ["icon.text"], autoRemoveDoneWorkers: true });
    l.sent.length = 0;
    s.getState().editProject("p1", { name: "Alpha", iconText: "AL", iconColor: "#2563eb", iconImage: null });
    expect(l.sent).toEqual([]);
  });
  it("project_deleted drops it and moves off the current project", () => {
    const l = link();
    const s = createProjectsStore(l);
    l.play("projects", [{ id: "p1", name: "A" }, { id: "p2", name: "B" }]);
    expect(s.getState().currentId).toBe("p1");
    s.getState().deleteProject("p1");
    expect(JSON.parse(l.sent.at(-1)!.data)).toEqual({ projectId: "p1" });
    l.play("project_deleted", { id: "p1", name: "A" });
    expect(s.getState().projects.map((p) => p.id)).toEqual(["p2"]);
    expect(s.getState().currentId).toBe("p2");
  });
});
