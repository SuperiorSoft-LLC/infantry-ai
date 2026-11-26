// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The card:news body read as headlines (no UI imports, so a plain test runs it).
export interface Headline {
  title: string;
  url: string;
  source?: string;
  time?: string;
  image?: string;
  icon?: string;
}

export const str = (v: unknown): string | undefined => (typeof v === "string" && v.trim() ? v.trim() : undefined);

/** The items of a card:news body that can be drawn: a title and an https link each. */
export function headlines(body: Record<string, unknown>): Headline[] {
  const items = Array.isArray(body.items) ? body.items : [];
  return items.flatMap((it) => {
    if (!it || typeof it !== "object") return [];
    const o = it as Record<string, unknown>;
    const title = str(o.title);
    const url = str(o.url);
    if (!title || !url || !/^https?:\/\//i.test(url)) return [];
    return [{ title, url, source: str(o.source), time: str(o.time), image: str(o.image), icon: str(o.icon) }];
  });
}
