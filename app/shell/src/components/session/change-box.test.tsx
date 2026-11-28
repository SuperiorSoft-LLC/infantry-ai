// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// PARITY-AUDIT-1 #27: the change box's preview count follows setting chat.changeBox.previewLines (default unchanged),
// and "Open full" shows the whole diff in the shared modal.
import { beforeEach, describe, expect, it, vi } from "vitest";
import { createElement } from "react";
import { renderToString } from "react-dom/server";
import { settingKey, type DiffLine } from "@infantry/client";

const st = vi.hoisted(() => ({ values: {} as Record<string, unknown> }));
vi.mock("@/components/settings/use-settings", () => ({
  useSettings: (sel: (s: { values: Record<string, unknown> }) => unknown) => sel({ values: st.values }),
  settingsStore: () => ({ getState: () => ({ ensureLoaded() {} }) }),
}));
vi.mock("@/daemon/daemon", () => ({ daemonStore: () => ({ getState: () => ({}) }) }));
vi.mock("@infantry/ui", async (orig) => ({
  ...(await orig<object>()),
  AppDialog: ({ open, children, title }: { open: boolean; children: unknown; title: string }) =>
    open ? createElement("div", { "data-testid": "dialog" }, title, children as never) : null,
}));

const { ChangeBox, ChangeBoxFull, CHANGE_BOX_PREVIEW_KEY } = await import("./change-box");
const { CHAT_NODE } = await import("./chat-settings");

const lines: DiffLine[] = Array.from({ length: 30 }, (_, i) => ({ kind: "add", text: `row${i}`, newNo: i + 1 }) as DiffLine);
const { TooltipProvider } = await import("@infantry/ui");
const box = () => renderToString(createElement(TooltipProvider, null, createElement(ChangeBox, { path: "a.ts", created: true, added: 30, removed: 0, lines })));
const rows = (html: string) => (html.match(/data-testid="diff-line-add"/g) ?? []).length;
beforeEach(() => (st.values = {}));

describe("change box preview setting", () => {
  it("previews 12 rows by default (unchanged)", () => {
    expect(rows(box())).toBe(12);
    expect(box()).toContain("+18 more lines");
  });
  it("previews the set count", () => {
    st.values = { [settingKey(CHAT_NODE, CHANGE_BOX_PREVIEW_KEY)]: 5 };
    expect(rows(box())).toBe(5);
    expect(box()).toContain("+25 more lines");
  });
  it("has an open-full action and no modal until it is used", () => {
    expect(box()).toContain('data-testid="change-box-open-full"');
    expect(box()).not.toContain('data-testid="change-box-modal"');
  });
  it("the open modal shows the file and every diff row", () => {
    const html = renderToString(createElement(ChangeBoxFull, { open: true, onOpenChange() {}, path: "a.ts", created: true, added: 30, removed: 0, lines }));
    expect(html).toContain("a.ts");
    expect(rows(html)).toBe(30);
  });
});
