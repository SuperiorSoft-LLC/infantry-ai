// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// SERVICES-2: the Services hub model and bug 28883c95 (a login page opening after sign-in).
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";
import { createQueryStore, type Envelope } from "../src";
import type { FrameLink } from "../src/stores/link";
import { accountProviders, accountQuerySpec, cardChip, hubView, mergedTabTarget, servicePages, shouldOpenApproval } from "../src/account";

const doc = {
  providers: [
    { id: "ss", name: "Infantry AI", description: "d", signedIn: true, plan: { name: "Pro" } },
    { id: "acme", name: "Acme", signedIn: false },
    { id: "w", name: "W", signedIn: false, flow: { state: "pending", userCode: "X" } },
  ],
  services: [{ id: "sites", name: "Sites", section: "infantry.sites" }, { id: "nosection", name: "N" }],
};

describe("services hub", () => {
  it("each provider gets its own correct card", () => {
    const ps = accountProviders(doc);
    expect(ps.map((p) => p.name)).toEqual(["Infantry AI", "Acme", "W"]);
    expect(ps.map((p) => cardChip(p).label)).toEqual(["Signed in", "Not signed in", "Waiting for approval"]);
    expect(ps[0].plan).toBe("Pro");
  });

  it("HOSTING-SIGNIN-1: one true state per card, never Connected next to signed out", () => {
    const chip = (p: Record<string, unknown>) => cardChip(accountProviders({ providers: [{ id: "h", ...p }] })[0]).label;
    expect(chip({ signedIn: true, signIn: "signed_in" })).toBe("Signed in");
    expect(chip({ signedIn: false, signIn: "expired" })).toBe("Sign-in expired");
    expect(chip({ signedIn: false, signIn: "signed_out" })).toBe("Not signed in");
    expect(chip({ signedIn: true, signIn: "unreachable" })).toBe("Service unreachable");
    expect(chip({ signedIn: true })).toBe("Signed in");
    expect(chip({ signedIn: false, signIn: "bogus" })).toBe("Not signed in");
  });

  it("a connected card opens its dashboard; signed out elsewhere returns to the list", () => {
    const ps = accountProviders(doc);
    expect(hubView(ps, null).kind).toBe("list");
    expect(hubView(ps, "acme").kind).toBe("list");
    expect(hubView(ps, "ss")).toMatchObject({ kind: "dashboard", provider: { id: "ss" } });
    expect(hubView(accountProviders({ providers: [{ id: "ss", signedIn: false }] }), "ss").kind).toBe("list");
  });

  it("pages are the services that name a section; a single page hides the menu", () => {
    const pages = servicePages(doc);
    expect(pages).toEqual([{ id: "sites", name: "Sites", section: "infantry.sites" }]);
    expect(pages.length > 1).toBe(false);
  });

  it("old Sites tab ids lead into Services", () => {
    expect(mergedTabTarget("sites")).toBe("services");
    expect(mergedTabTarget("infantry.sites")).toBe("infantry.account");
    expect(mergedTabTarget("mail")).toBeNull();
  });

  it("no Sites tab in the bar: both module.json copies say bar:false and the default layout omits it", () => {
    const root = resolve(__dirname, "../../../..");
    for (const p of ["plugins/official/Infantry_Sites/module.json", "rust/plugins/official/Infantry_Sites/module.json"]) {
      const m = JSON.parse(readFileSync(resolve(root, p), "utf8"));
      expect(m.tabs).toEqual([{ id: "infantry.sites", container: "center", bar: false }]);
    }
    expect(readFileSync(resolve(root, "app/shell/src/plugins/default-layout.ts"), "utf8")).not.toContain("infantry.sites");
  });
});

describe("the login page (bug 28883c95)", () => {
  const uri = "https://x/approve";
  it("a remounted card with a stale sign-in answer opens no page", () => {
    expect(shouldOpenApproval({ clickedHere: false, signedIn: true, uri, alreadyOpened: null })).toBe(false);
    expect(shouldOpenApproval({ clickedHere: false, signedIn: false, uri, alreadyOpened: null })).toBe(false);
  });

  it("a Sign in click opens it once, and never when signed in", () => {
    expect(shouldOpenApproval({ clickedHere: true, signedIn: false, uri, alreadyOpened: null })).toBe(true);
    expect(shouldOpenApproval({ clickedHere: true, signedIn: false, uri, alreadyOpened: uri })).toBe(false);
    expect(shouldOpenApproval({ clickedHere: true, signedIn: true, uri, alreadyOpened: null })).toBe(false);
  });

  it("opening Services signed in sends only account_status; a sign-out made elsewhere shows on the next read", () => {
    const sent: Envelope[] = [];
    const ls = new Set<(e: Envelope) => void>();
    const link: FrameLink = { sendFrame: (e) => (sent.push(e), true), onFrame: (l) => (ls.add(l), () => ls.delete(l)) };
    const store = createQueryStore(link, accountQuerySpec);
    const answer = (d: unknown) => ls.forEach((l) => l({ type: "account_status", data: JSON.stringify(d), requestId: sent[sent.length - 1].requestId }));
    store.getState().refresh();
    answer({ providers: [{ id: "ss", signedIn: true }] });
    expect(accountProviders(store.getState().data)[0].signedIn).toBe(true);
    store.getState().refresh(); // the tab's timer
    answer({ providers: [{ id: "ss", signedIn: false }] });
    expect(accountProviders(store.getState().data)[0].signedIn).toBe(false);
    expect(sent.every((e) => e.type === "account_status")).toBe(true);
  });
});
