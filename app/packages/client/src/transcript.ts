// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Frames -> transcript items. Pure: an item, once made, is never mutated; a change makes a new object in its place, so
// a memoised row re-renders only when its own item changed (finished messages never re-render while one streams).
import { Frame, json, type Envelope, type FileWrite, type FinishedPayload, type MessageOrigin, type ReplayGap, type StatusPayload } from "./wire";

export interface DiffLine {
  oldNo?: number;
  newNo?: number;
  kind: "add" | "del" | "ctx" | "hunk";
  text: string;
}

export type TranscriptItem =
  | { kind: "user"; id: string; seq: number; at: number; text: string }
  | { kind: "comms"; id: string; seq: number; at: number; from: string; text: string }
  | { kind: "text"; id: string; seq: number; text: string }
  | { kind: "thinking"; id: string; seq: number; count: number; text: string }
  | { kind: "tools"; id: string; seq: number; count: number; tool: string; activity: string }
  | { kind: "edit"; id: string; seq: number; path: string; created: boolean; added: number; removed: number; lines: DiffLine[]; truncated: boolean }
  | { kind: "note"; id: string; seq: number; text: string; tone?: "danger" }
  | { kind: "finished"; id: string; seq: number; outcome: string; exitCode: number | null; detail?: string }
  | { kind: "gap"; id: string; seq: number; evicted: number };

/** A message from someone other than the owner, as the Kotlin COMMS header writes it (legacy, pre-origin daemons). */
const COMMS_HEADER = /^\[(?:Infantry AI|AgentHQ) COMMS\] message \S+ from (.+?)(?: · .*)?$/m;

export function originLabel(o: MessageOrigin): string {
  switch (o.type) {
    case "agent":
      return [o.role ?? "agent", o.agentId ? `(${o.agentId.slice(0, 8)})` : o.sessionId ? `(${o.sessionId.slice(0, 8)})` : ""].filter(Boolean).join(" ");
    case "external":
      return [o.senderLabel ?? "external", o.channel ? `via ${o.channel}` : ""].filter(Boolean).join(" ");
    case "system":
      return o.kind ? `system · ${o.kind}` : "system";
  }
}

/** A text's lines; a final newline ends the last line rather than opening an empty one (Kotlin `linesOf`). */
export function linesOf(text: string): string[] {
  return text === "" ? [] : text.replace(/\n$/, "").split("\n").map((l) => l.replace(/\r$/, ""));
}

/** Above this many old x new lines an edit is drawn as all-removed then all-added rather than diffed (Kotlin `LCS_MAX_CELLS`). */
export const LCS_MAX_CELLS = 1_000_000;

/** The line diff of [old] against [next]: common lines are context, the rest `-` / `+`. Head and tail are matched first. */
export function lineDiff(old: string[], next: string[]): DiffLine[] {
  let head = 0;
  while (head < old.length && head < next.length && old[head] === next[head]) head++;
  let tail = 0;
  while (tail < old.length - head && tail < next.length - head && old[old.length - 1 - tail] === next[next.length - 1 - tail]) tail++;
  const a = old.slice(head, old.length - tail);
  const b = next.slice(head, next.length - tail);
  const out: DiffLine[] = [];
  let o = 1, n = 1;
  const ctx = (t: string) => out.push({ oldNo: o++, newNo: n++, kind: "ctx", text: t });
  const del = (t: string) => out.push({ oldNo: o++, kind: "del", text: t });
  const add = (t: string) => out.push({ newNo: n++, kind: "add", text: t });
  old.slice(0, head).forEach(ctx);
  if (a.length * b.length > LCS_MAX_CELLS) {
    a.forEach(del);
    b.forEach(add);
  } else {
    const w = b.length + 1;
    const t = new Uint32Array((a.length + 1) * w);
    for (let i = a.length - 1; i >= 0; i--)
      for (let j = b.length - 1; j >= 0; j--) t[i * w + j] = a[i] === b[j] ? t[(i + 1) * w + j + 1] + 1 : Math.max(t[(i + 1) * w + j], t[i * w + j + 1]);
    let i = 0, j = 0;
    while (i < a.length && j < b.length) {
      if (a[i] === b[j]) { ctx(a[i]); i++; j++; }
      else if (t[(i + 1) * w + j] >= t[i * w + j + 1]) del(a[i++]);
      else add(b[j++]);
    }
    while (i < a.length) del(a[i++]);
    while (j < b.length) add(b[j++]);
  }
  old.slice(old.length - tail).forEach(ctx);
  return out;
}

/** The diff lines of one file write: a unified patch, search/replace edits, or a whole new file. */
export function writeToDiff(w: FileWrite): { lines: DiffLine[]; added: number; removed: number } {
  const lines: DiffLine[] = [];
  if (w.patch) {
    let o = 0, n = 0;
    for (const raw of w.patch.split("\n")) {
      const h = /^@@ -(\d+)(?:,\d+)? \+(\d+)/.exec(raw);
      if (h) { o = Number(h[1]); n = Number(h[2]); continue; }
      if (raw.startsWith("+++") || raw.startsWith("---") || raw.startsWith("diff ") || raw.startsWith("index ")) continue;
      if (raw.startsWith("+")) lines.push({ newNo: n++, kind: "add", text: raw.slice(1) });
      else if (raw.startsWith("-")) lines.push({ oldNo: o++, kind: "del", text: raw.slice(1) });
      else if (raw.startsWith(" ")) lines.push({ oldNo: o++, newNo: n++, kind: "ctx", text: raw.slice(1) });
    }
  } else if (w.edits && w.edits.length > 0) {
    const edits = w.edits;
    edits.forEach((e, i) => {
      if (edits.length > 1) lines.push({ kind: "hunk", text: `@@ edit ${i + 1} of ${edits.length} @@` });
      lines.push(...lineDiff(linesOf(e.oldText), linesOf(e.newText)));
    });
  } else if (w.content != null) {
    linesOf(w.content).forEach((t, i) => lines.push({ newNo: i + 1, kind: "add", text: t }));
  }
  let added = 0, removed = 0;
  for (const l of lines) l.kind === "add" ? added++ : l.kind === "del" && removed++;
  return { lines, added, removed };
}

/**
 * Apply a batch of one session's frames to its items. The array is copied once per batch; items that change are
 * replaced by new objects, the rest keep their identity.
 */
export function applyFrames(items: readonly TranscriptItem[], frames: readonly Envelope[]): TranscriptItem[] {
  const out = items.slice();
  const last = () => out[out.length - 1];
  for (const f of frames) {
    const seq = f.sequence ?? 0;
    const id = `${f.type}:${seq}:${out.length}`;
    switch (f.type) {
      case Frame.TURN: {
        const header = f.origin ? null : COMMS_HEADER.exec(f.data);
        if (f.origin) out.push({ kind: "comms", id, seq, at: Date.now(), from: originLabel(f.origin), text: f.data });
        else if (header) out.push({ kind: "comms", id, seq, at: Date.now(), from: header[1], text: f.data.replace(COMMS_HEADER, "").trim() });
        else out.push({ kind: "user", id, seq, at: Date.now(), text: f.data });
        break;
      }
      case Frame.TEXT_DELTA: {
        const l = last();
        if (l?.kind === "text") out[out.length - 1] = { ...l, text: joinDelta(l.text, f.data) };
        else out.push({ kind: "text", id, seq, text: f.data });
        break;
      }
      case Frame.REASONING: {
        const l = last();
        if (l?.kind === "thinking") out[out.length - 1] = { ...l, count: l.count + 1, text: joinDelta(l.text, f.data) };
        else out.push({ kind: "thinking", id, seq, count: 1, text: f.data });
        break;
      }
      case Frame.STATUS: {
        const s = json<StatusPayload>(f.data);
        if (!s) break;
        if (s.write) {
          const d = writeToDiff(s.write);
          out.push({ kind: "edit", id, seq, path: s.write.path, created: s.write.content != null && !s.write.patch && !(s.write.edits?.length), ...d, truncated: s.write.truncated === true });
        } else if (s.tool && s.activity) {
          const l = last();
          if (l?.kind === "tools") {
            if (l.tool !== s.tool || l.activity !== s.activity) out[out.length - 1] = { ...l, count: l.count + 1, tool: s.tool, activity: s.activity };
          } else out.push({ kind: "tools", id, seq, count: 1, tool: s.tool, activity: s.activity });
        }
        break;
      }
      case Frame.STDERR:
        if (f.data.trim()) out.push({ kind: "note", id, seq, text: f.data.trimEnd() });
        break;
      case Frame.ERROR:
        out.push({ kind: "note", id, seq, text: f.data, tone: "danger" });
        break;
      case Frame.FINISHED: {
        const p = json<FinishedPayload>(f.data);
        if (p) out.push({ kind: "finished", id, seq, outcome: p.outcome, exitCode: p.exitCode ?? null, detail: p.detail?.trim() || undefined });
        break;
      }
      case Frame.REPLAY_GAP: {
        const g = json<ReplayGap>(f.data);
        if (g && g.evictedCount > 0) out.push({ kind: "gap", id, seq, evicted: g.evictedCount });
        break;
      }
    }
  }
  return out;
}

/** Deltas are chunks of one message and join as sent (the Kotlin client does the same). */
function joinDelta(a: string, b: string): string {
  return a + b;
}

/** The text a transcript search reads from one item. */
function searchText(item: TranscriptItem): string {
  const o = item as unknown as Record<string, unknown>;
  return ["text", "from", "tool", "activity", "path", "outcome"].map((k) => (typeof o[k] === "string" ? (o[k] as string) : "")).join("\n");
}

/** Indices of [items] whose text holds [query] (case-insensitive); none for a blank query. */
export function searchItems(items: readonly TranscriptItem[], query: string): number[] {
  const q = query.trim().toLowerCase();
  if (!q) return [];
  const out: number[] = [];
  items.forEach((it, i) => {
    if (searchText(it).toLowerCase().includes(q)) out.push(i);
  });
  return out;
}
