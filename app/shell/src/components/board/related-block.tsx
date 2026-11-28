// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// PARITY-AUDIT-1 #9: the ticket dialog's related-tickets block (Kotlin RelatedView). It reads the daemon's RELATED (auto)
// block atop the description (series, linked and same-file tickets with their files) plus the ticket's links, each ticket
// once; a row opens that ticket. Nothing is fetched or stored here.
import type { BoardTicket } from "@infantry/client";
import { Chip } from "@infantry/ui";
import { shortId } from "./ticket-card";

export const RELATED_BEGIN = "<!-- RELATED (auto) -->";
export const RELATED_END = "<!-- /RELATED -->";
const LINE = /^- (\S+) "(.*)" \[([^\]]+)\] \(([^)]*)\)(?: files: (.*))?$/;

export interface RelatedItem {
  ticketId: string;
  title: string;
  status: string;
  reason: string;
  files?: string;
}

/** The block's ticket lines, then each link not already listed (titles and status from [known] when the board holds them). */
export function relatedItems(description: string | undefined, links: BoardTicket["links"], known: Record<string, BoardTicket> = {}): RelatedItem[] {
  const out: RelatedItem[] = [];
  const d = description ?? "";
  const b = d.indexOf(RELATED_BEGIN);
  const e = d.indexOf(RELATED_END);
  if (b >= 0 && e > b) {
    for (const raw of d.slice(b + RELATED_BEGIN.length, e).split("\n")) {
      const m = LINE.exec(raw.trim());
      if (m) out.push({ ticketId: m[1], title: m[2], status: m[3], reason: m[4], files: m[5] });
    }
  }
  for (const l of links ?? []) {
    if (out.some((x) => x.ticketId === l.ticketId)) continue;
    const t = known[l.ticketId];
    out.push({ ticketId: l.ticketId, title: t?.title ?? "", status: t?.status ?? "", reason: l.type.replace(/_/g, " ") });
  }
  return out;
}

export function RelatedBlock({ items, onOpen }: { items: RelatedItem[]; onOpen?: (id: string) => void }) {
  if (!items.length) return null;
  return (
    <section aria-label="Related tickets" className="space-y-1.5 rounded-lg border p-2">
      <h4 className="text-xs font-semibold">{`Related tickets (${items.length})`}</h4>
      <ul className="space-y-1">
        {items.map((r) => (
          <li key={r.ticketId}>
            <button
              type="button"
              disabled={!onOpen}
              onClick={() => onOpen?.(r.ticketId)}
              className="flex w-full flex-wrap items-center gap-2 rounded-md px-1 py-0.5 text-left text-xs hover:bg-muted/50 disabled:cursor-default"
            >
              <span className="font-mono text-info">{shortId(r.ticketId)}</span>
              {r.status && <Chip>{r.status}</Chip>}
              <span className="min-w-0 flex-1 truncate">{r.title}</span>
              <span className="text-muted-foreground">{r.reason}</span>
              {r.files && <span className="w-full truncate font-mono text-[11px] text-muted-foreground">{r.files}</span>}
            </button>
          </li>
        ))}
      </ul>
    </section>
  );
}
