// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Chat cards (CARD-2, after the Kotlin ChatCards.kt `chat.cards` registry): a `card:<type>` fence draws with the renderer
// registered for its type. A type with no renderer draws as the generic card (title, text, link, fields, item list) with
// a "JSON" toggle for the raw body, instead of a bare code block. A renderer that throws falls back to the generic card.
// PLUGIN-AGENTS-1: a web UI plugin draws its own card types through the `chat.card` slot (`{"slot": "chat.card", "id": ..,
// "card": "<type>"}`); that view wins over an in-app renderer. While the plugin is loading, disabled or removed, the card
// draws generic, so an old message of a removed plugin still reads.
import { Component, useState, type ComponentType, type ReactNode } from "react";
import { Braces, ExternalLink as ExternalLinkIcon, RefreshCw } from "lucide-react";
import { ExternalLink } from "@/components/external-link";
import type { ChatCard } from "@infantry/client";
import { cn } from "@infantry/ui";
import { PluginView, useSlotFills } from "@/plugins/web-plugins";

export type ChatCardRenderer = ComponentType<{ card: ChatCard }>;
const renderers = new Map<string, ChatCardRenderer>();

/** A plugin's renderer for `card:<type>` fences; returns the unregister call. */
export function registerChatCard(type: string, renderer: ChatCardRenderer): () => void {
  renderers.set(type, renderer);
  return () => {
    if (renderers.get(type) === renderer) renderers.delete(type);
  };
}

const str = (v: unknown): string | undefined => (typeof v === "string" && v.trim() ? v : undefined);
const pick = (o: Record<string, unknown>, keys: string[]) => keys.map((k) => str(o[k])).find(Boolean);
const safeUrl = (v: unknown) => {
  const s = str(v);
  return s && /^(https?:|mailto:)/i.test(s) ? s : undefined;
};
const TITLE = ["title", "name", "headline", "label"];
const TEXT = ["fallback", "text", "summary", "description", "body", "message"];
const LINK = ["url", "link", "href"];
const SHOWN = new Set([...TITLE, ...TEXT, ...LINK]);

function Item({ v }: { v: unknown }) {
  if (v && typeof v === "object" && !Array.isArray(v)) {
    const o = v as Record<string, unknown>;
    const title = pick(o, TITLE) ?? pick(o, TEXT) ?? JSON.stringify(o);
    const url = safeUrl(o.url ?? o.link ?? o.href);
    return url ? (
      <ExternalLink href={url} className="text-info hover:underline">
        {title}
      </ExternalLink>
    ) : (
      <span>{title}</span>
    );
  }
  return <span>{String(v)}</span>;
}

/** The card any type draws as when no plugin renders it. */
export function GenericCard({ card }: { card: ChatCard }) {
  const [raw, setRaw] = useState(false);
  const b = card.body;
  const title = pick(b, TITLE);
  const text = pick(b, TEXT);
  const url = safeUrl(b.url ?? b.link ?? b.href);
  const rest = Object.entries(b).filter(([k]) => !SHOWN.has(k));
  const fields = rest.filter(([, v]) => v === null || ["string", "number", "boolean"].includes(typeof v));
  const lists = rest.filter(([, v]) => Array.isArray(v)) as [string, unknown[]][];
  return (
    <div className="rounded-lg border bg-card p-3 text-[13px]" data-card={card.type}>
      <div className="flex items-start gap-2">
        <div className="min-w-0 flex-1">
          <div className="text-[10px] tracking-wide text-muted-foreground uppercase">{card.type}</div>
          {title && <div className="font-semibold">{title}</div>}
        </div>
        {url && (
          <ExternalLink href={url} aria-label="Open link" className="text-muted-foreground hover:text-foreground">
            <ExternalLinkIcon className="size-4" />
          </ExternalLink>
        )}
        <button type="button" onClick={() => setRaw((r) => !r)} aria-pressed={raw} aria-label="Show JSON" className="text-muted-foreground hover:text-foreground">
          <Braces className="size-4" />
        </button>
      </div>
      {raw ? (
        <pre className="mt-2 overflow-x-auto font-mono text-[11.5px]">{JSON.stringify(b, null, 2)}</pre>
      ) : (
        <>
          {text && <p className="mt-1 whitespace-pre-wrap text-muted-foreground">{text}</p>}
          {fields.length > 0 && (
            <dl className="mt-2 grid grid-cols-[auto_1fr] gap-x-3 gap-y-0.5 text-xs">
              {fields.map(([k, v]) => (
                <div key={k} className="contents">
                  <dt className="text-muted-foreground">{k}</dt>
                  <dd className="truncate">{String(v)}</dd>
                </div>
              ))}
            </dl>
          )}
          {lists.map(([k, items]) => (
            <div key={k} className="mt-2">
              <div className="text-xs text-muted-foreground">{k}</div>
              <ul className="list-disc space-y-0.5 pl-5 text-xs">
                {items.map((v, i) => (
                  <li key={i}>
                    <Item v={v} />
                  </li>
                ))}
              </ul>
            </div>
          ))}
        </>
      )}
    </div>
  );
}

class Guard extends Component<{ fallback: ReactNode; children: ReactNode }, { failed: boolean }> {
  state = { failed: false };
  static getDerivedStateFromError() {
    return { failed: true };
  }
  render() {
    return this.state.failed ? this.props.fallback : this.props.children;
  }
}

export function ChatCardView({ card }: { card: ChatCard }) {
  const fill = useSlotFills("chat.card").find((f) => f.contribution.card === card.type && f.entry.state === "ready" && f.entry.views[f.contribution.id]);
  if (fill) return <PluginView plugin={fill.plugin} id={fill.contribution.id} props={{ card }} frame="card" />;
  const R = renderers.get(card.type);
  if (!R) return <GenericCard card={card} />;
  return (
    <Guard fallback={<GenericCard card={card} />}>
      <R card={card} />
    </Guard>
  );
}

/** A later fence of an addressed card: the card above already shows the folded state. */
export function CardUpdateLine({ type }: { type: string }) {
  return (
    <div className={cn("flex items-center gap-1.5 text-[11px] text-muted-foreground")}>
      <RefreshCw className="size-3" /> Updated the {type} card above
    </div>
  );
}
