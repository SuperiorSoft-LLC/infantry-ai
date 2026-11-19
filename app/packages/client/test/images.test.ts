// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
import { describe, expect, it } from "vitest";
import { fetchImage, imageRefs, imageTag, uploadImage, type Envelope } from "../src";

function link() {
  const taps = new Set<(e: Envelope) => void>();
  const sent: Envelope[] = [];
  return {
    sent,
    sendFrame: (e: Envelope) => (sent.push(e), true),
    onFrame: (l: (e: Envelope) => void) => (taps.add(l), () => void taps.delete(l)),
    answer: (type: string, data: unknown) => taps.forEach((t) => t({ type, requestId: sent.at(-1)!.requestId, data: typeof data === "string" ? data : JSON.stringify(data) })),
  };
}

const ID = `${"a".repeat(64)}.png`;

describe("chat images", () => {
  it("splits a message into its text and image references", () => {
    const path = `C:\\p\\.infantry\\uploads\\chat\\${ID.toUpperCase()}`;
    expect(imageRefs(`look\n${imageTag(path)}`)).toEqual({ text: "look", refs: [{ path, id: ID }] });
    expect(imageRefs("plain")).toEqual({ text: "plain", refs: [] });
  });

  it("uploads, reads a refusal, and fetches once", async () => {
    const l = link();
    const up = uploadImage(l, "p1", "AAA=", "image/png");
    expect(JSON.parse(l.sent[0].data)).toEqual({ projectId: "p1", purpose: "chat", base64: "AAA=", mimeType: "image/png" });
    l.answer("image", { path: "/p/.infantry/uploads/chat/x.png", mimeType: "image/png", bytes: 3 });
    expect(await up).toEqual({ ok: true, path: "/p/.infantry/uploads/chat/x.png", mimeType: "image/png" });
    const bad = uploadImage(l, "p1", "AAA=");
    l.answer("error", "image_upload refused: not a png image");
    expect(await bad).toEqual({ ok: false, text: "not a png image" });
    const f = fetchImage(l, ID);
    l.answer("image_bytes", { id: ID, mimeType: "image/png", base64: "QQ==" });
    expect(await f).toBe("data:image/png;base64,QQ==");
    const n = l.sent.length;
    expect(await fetchImage(l, ID)).toBe("data:image/png;base64,QQ==");
    expect(l.sent.length).toBe(n);
  });
});
