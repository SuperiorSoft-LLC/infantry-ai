// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Chat images (Kotlin IMG-1 / IMG-2): `image_upload` {projectId, purpose: "chat", fileName?, mimeType?, base64} answers
// `image` {path, relPath, sha256, bytes, mimeType}; the prompt carries each one as `[image: <absolute path>]`, where it
// stands. A message's thumbnails come back through `image_fetch` {id} -> `image_bytes` {id, mimeType, base64} or
// {id, missing: true}; the id is the stored file name, `<sha256>.<ext>`.
import { askFrame } from "./tools";
import type { FrameLink } from "./stores/link";

/** How an attached image travels in a prompt (Kotlin `ChatAttachments.image`). */
export const imageTag = (path: string): string => `[image: ${path}]`;

/** How any other attached file travels in a prompt: its stored path, which every harness can read. */
export const fileTag = (path: string): string => `[file: ${path}]`;

/** The plain sentence shown when the upload frame is not served: its plugin (Infantry_Chat) is off. */
export const UPLOAD_UNAVAILABLE = "Attachments need the Chat plugin. Turn it on in Plugins.";

const IMAGE_REF = /\[image: ([^\]\n]+)\]/g;

export interface ImageRef {
  path: string;
  /** The upload id (`<sha256>.<ext>`): the path's file name. */
  id: string;
}

/** [text] without its `[image: …]` references, and those references in order. */
export function imageRefs(text: string): { text: string; refs: ImageRef[] } {
  const refs: ImageRef[] = [];
  const rest = text.replace(IMAGE_REF, (_, p: string) => {
    const path = p.trim();
    refs.push({ path, id: (path.split(/[\\/]/).pop() ?? path).toLowerCase() });
    return "";
  });
  return { text: refs.length ? rest.replace(/\n{3,}/g, "\n\n").trim() : text, refs };
}

export type UploadAnswer = { ok: true; path: string; mimeType: string } | { ok: false; text: string };

/** Uploads one chat image (purpose "chat") or any other file (purpose "file") of [projectId]; the answer's path is what the prompt names. */
export async function uploadImage(link: FrameLink, projectId: string, base64: string, mimeType?: string, fileName?: string, purpose: "chat" | "file" = "chat"): Promise<UploadAnswer> {
  const body: Record<string, string> = { projectId, purpose, base64 };
  if (mimeType) body.mimeType = mimeType;
  if (fileName) body.fileName = fileName;
  const a = await askFrame(link, { type: "image_upload", data: JSON.stringify(body) });
  if (!a.ok) {
    const text = a.text.replace(/^image_upload refused: /, "");
    return { ok: false, text: /does not serve that frame/.test(text) ? UPLOAD_UNAVAILABLE : text };
  }
  try {
    const v = JSON.parse(a.env.data) as { path?: string; mimeType?: string };
    return v.path ? { ok: true, path: v.path, mimeType: v.mimeType ?? "" } : { ok: false, text: "the daemon stored no path" };
  } catch {
    return { ok: false, text: "the daemon's answer was not JSON" };
  }
}

const fetched = new Map<string, Promise<string | null>>();

/** A `data:` URL of uploaded image [id], or null when it is missing; each id is fetched once per window. */
export function fetchImage(link: FrameLink, id: string): Promise<string | null> {
  let p = fetched.get(id);
  if (!p) {
    p = askFrame(link, { type: "image_fetch", data: JSON.stringify({ id }) }).then((a) => {
      if (!a.ok) return null;
      try {
        const v = JSON.parse(a.env.data) as { mimeType?: string; base64?: string; missing?: boolean };
        return v.base64 && !v.missing ? `data:${v.mimeType || "image/png"};base64,${v.base64}` : null;
      } catch {
        return null;
      }
    });
    p.then((url) => url === null && fetched.delete(id));
    fetched.set(id, p);
  }
  return p;
}
