// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// PARITY-AUDIT-1 #7: the ticket dialog's executor section.
import { describe, expect, it } from "vitest";
import { createElement } from "react";
import { renderToString } from "react-dom/server";
import type { BoardTicket } from "@infantry/client";
import { INHERIT } from "@/components/dialogs/picker-field";
import { effectiveLine, executorDraft, executorForm, TicketExecutor } from "./ticket-executor";

const base: BoardTicket = { id: "ticket-1", title: "t", status: "todo", position: 0 };

describe("executor save fields", () => {
  it("choosing role, provider, model, weight and effort sends them", () => {
    const v = { agentId: "role:frontend", provider: "claude", model: "sonnet", weight: "3", effort: "high" };
    expect(executorDraft(base, v)).toEqual({ agentId: "role:frontend", provider: "claude", model: "sonnet", weight: 3, effort: "high" });
  });
  it("clearing a pin sends the clear value; untouched pins send nothing", () => {
    const t = { ...base, agentId: "role:frontend", provider: "claude", model: "sonnet", weight: 2, effort: "low" };
    const v = { ...executorForm(t), provider: INHERIT, model: INHERIT, weight: INHERIT };
    expect(executorDraft(t, v)).toEqual({ provider: "", model: "", weight: 0 });
    expect(executorDraft(t, executorForm(t))).toEqual({});
  });
  it("a new ticket with nothing picked sends nothing", () => {
    expect(executorDraft(undefined, executorForm(undefined))).toEqual({});
  });
});

describe("effective executor", () => {
  it("names each part's source: ticket, role, rule", () => {
    expect(effectiveLine({ role: "frontend", provider: { value: "claude", source: "ticket" }, model: { value: "sonnet", source: "role" } })).toBe(
      "role frontend · provider claude (ticket) · model sonnet (role)",
    );
    expect(effectiveLine({ provider: { value: "codex", source: "rule" }, model: { value: "o3", source: "rule" }, rule: "cheap" })).toBe(
      "provider codex (rule: cheap) · model o3 (rule: cheap)",
    );
  });
  it("the section shows it", () => {
    const html = renderToString(
      createElement(TicketExecutor, {
        value: executorForm(undefined),
        onChange: () => {},
        roles: {},
        harnesses: [],
        catalog: [],
        effective: { provider: { value: "claude", source: "ticket" } },
      }),
    );
    expect(html).toContain("Runs as: provider claude (ticket)");
  });
});
