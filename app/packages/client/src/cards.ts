// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Chat cards (CARD-1/CARD-UPDATE-1, after the Kotlin model/ChatCards.kt): a fenced block whose info string is
// `card:<type> [id=..] [op=set|append|replace]` and whose body is one JSON object is a card. Anything else (a bad
// header, a body that is not one object, a fence still open while the reply streams) stays a code block.
// foldCards folds later fences of the same id into the first one, so an addressed card shows its latest state.

export type CardOp = "set" | "append" | "replace";

export interface ChatCard {
  /** The type without the `card:` prefix (`news`). */
  type: string;
  id: string;
  op: CardOp;
  body: Record<string, unknown>;
}

export const CARD_PREFIX = "card:";
const OPS = new Set<CardOp>(["set", "append", "replace"]);
const TYPE = /^[\p{L}\p{N}_.-]+$/u;

/** The card for one fence, or null when it is not a valid card fence. */
export function cardOf(lang: string, text: string, open = false): ChatCard | null {
  if (open) return null;
  const words = lang.trim().split(/\s+/);
  const head = words[0] ?? "";
  if (!head.startsWith(CARD_PREFIX)) return null;
  const type = head.slice(CARD_PREFIX.length);
  if (!TYPE.test(type)) return null;
  let id = "";
  let op: CardOp = "set";
  for (const w of words.slice(1)) {
    const eq = w.indexOf("=");
    if (eq <= 0) return null;
    const k = w.slice(0, eq);
    const v = w.slice(eq + 1);
    if (k === "id") id = v;
    else if (k === "op" && OPS.has(v as CardOp)) op = v as CardOp;
    else return null;
  }
  let body: unknown;
  try {
    body = JSON.parse(text.trim());
  } catch {
    return null;
  }
  if (!body || typeof body !== "object" || Array.isArray(body)) return null;
  return { type, id, op, body: body as Record<string, unknown> };
}

/** [base] after one update: `set` merges keys, `replace` swaps the body, `append` concatenates arrays and strings. */
export function applyCard(base: ChatCard, next: ChatCard): ChatCard {
  if (next.op === "replace") return { ...base, body: next.body };
  if (next.op === "set") return { ...base, body: { ...base.body, ...next.body } };
  const body = { ...base.body };
  for (const [k, v] of Object.entries(next.body)) {
    const old = body[k];
    if (Array.isArray(old)) body[k] = old.concat(Array.isArray(v) ? v : [v]);
    else if (typeof old === "string" && typeof v === "string") body[k] = old + v;
    else body[k] = v;
  }
  return { ...base, body };
}

/**
 * Folds addressed cards in reading order: index i of the result is the card to draw at i, `"update"` for a later
 * fence of an id seen before (it draws as one short line), or null for no card.
 */
export function foldCards(cards: (ChatCard | null)[]): (ChatCard | "update" | null)[] {
  const first = new Map<string, number>();
  const out: (ChatCard | "update" | null)[] = cards.slice();
  cards.forEach((c, i) => {
    if (!c || !c.id) return;
    const key = `${c.type}\u0000${c.id}`;
    const at = first.get(key);
    if (at === undefined) first.set(key, i);
    else {
      out[at] = applyCard(out[at] as ChatCard, c);
      out[i] = "update";
    }
  });
  return out;
}
