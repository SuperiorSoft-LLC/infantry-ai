// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
import { describe, expect, it } from "vitest";
import { createSecretsStore, placeholderOf, type Envelope } from "../src";

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
const state = { protection: "os", secrets: [], entries: [{ name: "db", fields: [{ name: "PASS", tail: "••••" }] }], locked: false };

describe("secrets store", () => {
  it("loads names only, sets a field and keeps a revealed value out of the store", async () => {
    const l = link();
    const s = createSecretsStore(l);
    s.getState().ensureLoaded();
    expect(l.sent[0]).toMatchObject({ type: "secret_status" });
    l.push({ type: "secret_state", data: JSON.stringify(state) });
    expect(s.getState().entries[0].name).toBe("db");
    expect(placeholderOf(s.getState().entries[0], "PASS")).toBe("{{secret:db}}");

    const p = s.getState().setField("db", "USER", "fake-user");
    const req = l.sent[1];
    expect(req.type).toBe("secret_field_set");
    l.push({ type: "secret_state", data: JSON.stringify(state), requestId: req.requestId });
    await p;

    const r = s.getState().reveal("db", "PASS", "copy");
    l.push({ type: "secret_reveal", data: JSON.stringify({ name: "db", field: "PASS", value: "fake-pass", method: "windows-hello", showSeconds: 30, clearSeconds: 30 }), requestId: l.sent[2].requestId });
    expect((await r).value).toBe("fake-pass");
    expect(JSON.stringify(s.getState())).not.toContain("fake-pass");

    const refused = s.getState().reveal("db", "PASS", "reveal");
    l.push({ type: "error", data: "secret_reveal refused: no Hello", requestId: l.sent[3].requestId });
    await expect(refused).rejects.toBe("secret_reveal refused: no Hello");
  });

  it("starts the in-app sign-in and sends a pasted token to the daemon only", async () => {
    const l = link();
    const s = createSecretsStore(l);
    const start = s.getState().login("harness.claude.token", "start");
    expect(l.sent[0]).toMatchObject({ type: "secret_login" });
    expect(JSON.parse(l.sent[0].data)).toEqual({ name: "harness.claude.token", action: "start", text: "" });
    l.push({ type: "secret_state", data: JSON.stringify({ ...state, secrets: [{ name: "harness.claude.token", label: "t", set: false, tail: "", canLogin: true, loginState: "running", loginUrl: "https://claude.ai/x" }] }), requestId: l.sent[0].requestId });
    await start;
    expect(s.getState().builtIns[0]).toMatchObject({ loginState: "running", loginUrl: "https://claude.ai/x" });
    const paste = s.getState().login("harness.claude.token", "input", "sk-ant-oat01-secret");
    l.push({ type: "secret_state", data: JSON.stringify(state), requestId: l.sent[1].requestId });
    await paste;
    expect(JSON.stringify(s.getState())).not.toContain("sk-ant-oat01-secret");
  });

  it("signs out and starts over: the answer is the daemon's fresh row, not a cached one", async () => {
    const l = link();
    const s = createSecretsStore(l);
    const row = { name: "harness.claude.key", label: "Claude", set: false, tail: "", canLogin: true, canLogout: true };
    const out = s.getState().login(row.name, "signout");
    expect(JSON.parse(l.sent[0].data)).toEqual({ name: row.name, action: "signout", text: "" });
    l.push({ type: "secret_state", data: JSON.stringify({ ...state, secrets: [{ ...row, signedIn: "false", loginState: "" }] }), requestId: l.sent[0].requestId });
    await out;
    expect(s.getState().builtIns[0]).toMatchObject({ canLogout: true, signedIn: "false", loginState: "" });
    s.getState().refresh();
    expect(l.sent[1]).toMatchObject({ type: "secret_status" });
  });
});
