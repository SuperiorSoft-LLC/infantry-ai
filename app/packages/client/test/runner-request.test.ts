// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
import { describe, expect, it } from "vitest";
import { createHarnessInstallStore, createQueryStore, createSecretsStore, runnerLink, runnerRequest, aimed, type Envelope } from "../src";
import type { FrameLink } from "../src/stores/link";

function fake() {
  const sent: Envelope[] = [];
  const listeners = new Set<(e: Envelope) => void>();
  const link: FrameLink = { sendFrame: (e) => (sent.push(e), true), onFrame: (l) => (listeners.add(l), () => listeners.delete(l)) };
  return { link, sent, push: (e: Envelope) => listeners.forEach((l) => l(e)) };
}

describe("runner-aimed frames", () => {
  it("sends the plain frame for local or no runner, runner_frame for any other", () => {
    const env = { type: "providers", data: "{}", requestId: "a" };
    expect(aimed(undefined, env)).toBe(env);
    expect(aimed("local", env)).toBe(env);
    const w = aimed("box", env);
    expect(w.type).toBe("runner_frame");
    expect(JSON.parse(w.data)).toEqual({ runner: "box", frame: "providers", data: "{}" });
    expect(w.requestId).toBe("a");
  });

  it("runnerRequest resolves with the inner answer and rejects with the refusal", async () => {
    const f = fake();
    const p = runnerRequest(f.link, "box", "models", "{}");
    expect(f.sent[0].type).toBe("runner_frame");
    f.push({ type: "models", data: "[1]", requestId: f.sent[0].requestId });
    expect((await p).type).toBe("models");
    const q = runnerRequest(f.link, "box", "models");
    f.push({ type: "error", data: "runner_frame refused: runner box is offline", requestId: f.sent[1].requestId });
    await expect(q).rejects.toThrow("offline");
    const r = runnerRequest(f.link, undefined, "providers");
    expect(f.sent[2].type).toBe("providers");
    f.push({ type: "providers", data: "[]", requestId: f.sent[2].requestId });
    expect((await r).data).toBe("[]");
  });

  it("an install store on a runner link sends runner_frame for its status and install calls, and ignores the local daemon's pushes", () => {
    const f = fake();
    const store = createHarnessInstallStore(runnerLink(f.link, "box"));
    store.getState().refresh();
    store.getState().send("harness_install", JSON.stringify({ ids: ["pi"] }));
    expect(f.sent.map((e) => [e.type, JSON.parse(e.data).frame])).toEqual([["runner_frame", "harness_install_status"], ["runner_frame", "harness_install"]]);
    const report = JSON.stringify({ harnesses: [{ id: "pi" }], busy: false });
    f.push({ type: "harness_install_status", data: report });
    expect(store.getState().loaded).toBe(false);
    f.push({ type: "harness_install_status", data: report, requestId: f.sent[0].requestId });
    expect(store.getState().loaded).toBe(true);
  });

  it("a local link is the same link, plain frames", () => {
    const f = fake();
    expect(runnerLink(f.link, "local")).toBe(f.link);
    createHarnessInstallStore(runnerLink(f.link, undefined)).getState().refresh();
    expect(f.sent[0].type).toBe("harness_install_status");
  });
});

describe("the wizard's other calls, aimed", () => {
  const inner = (e: Envelope) => ({ frame: JSON.parse(e.data).frame as string, data: JSON.parse(e.data).data as string });

  it("providers, models and the secret rows (key set, sign-in start / input / cancel) all go as runner_frame with the runner name", async () => {
    const f = fake();
    const link = runnerLink(f.link, "box");
    const providers = createQueryStore<unknown[]>(link, { prefix: "p", request: "providers", answers: ["providers"], empty: [], parse: (r) => (Array.isArray(r) ? r : null) });
    const models = createQueryStore<unknown[]>(link, { prefix: "m", request: "models", answers: ["models"], empty: [], parse: (r) => (Array.isArray(r) ? r : null) });
    const secrets = createSecretsStore(link);
    providers.getState().refresh("{}");
    models.getState().refresh("{}");
    secrets.getState().refresh();
    // The wrapper only sees its own answers: this one is the local daemon's push and changes nothing.
    f.push({ type: "providers", data: "[1]" });
    expect(providers.getState().loaded).toBe(false);
    const ask = (frame: string, body: object) => {
      const done = frame === "secret_field_set" ? secrets.getState().setField("harness.pi.key", "value", "k-123") : secrets.getState().login("harness.pi.token", frame as "start", "code-9");
      void body;
      const sent = f.sent[f.sent.length - 1];
      f.push({ type: "secret_state", data: JSON.stringify({ secrets: [], entries: [] }), requestId: sent.requestId });
      return done;
    };
    await ask("secret_field_set", {});
    await ask("start", {});
    await ask("input", {});
    await ask("cancel", {});
    expect(f.sent.every((e) => e.type === "runner_frame")).toBe(true);
    expect(f.sent.map((e) => JSON.parse(e.data).runner)).toEqual(Array(f.sent.length).fill("box"));
    expect(f.sent.map((e) => inner(e).frame)).toEqual(["providers", "models", "secret_status", "secret_field_set", "secret_login", "secret_login", "secret_login"]);
    // The key travels in the body of the frame only, never in the envelope's type or in another field.
    expect(f.sent.filter((e) => JSON.stringify({ ...e, data: "" }).includes("k-123"))).toEqual([]);
  });

  it("the New agent lists follow the runner: a store on box asks box, one on local asks plain", () => {
    const f = fake();
    for (const runner of ["box", "local"]) {
      createQueryStore<unknown[]>(runnerLink(f.link, runner), { prefix: "x", request: "providers", answers: ["providers"], empty: [], parse: () => [] }).getState().refresh("{}");
    }
    expect(f.sent.map((e) => e.type)).toEqual(["runner_frame", "providers"]);
  });
});
