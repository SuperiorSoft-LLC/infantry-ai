// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The card:news body ({title?, items: [{title, url, source?, time?, image?, icon?}]}) as a list of headline cards: a
// thumbnail (else the source icon, else a letter badge), the headline as a link, then source and "2h ago".
import { ago, cn } from "@infantry/ui";
import { openExternal, useCardImage, type FrameLink } from "@infantry/ui-plugin-api";
import { headlines, str, type Headline } from "./headlines";

function Thumb({ link, h }: { link: FrameLink; h: Headline }) {
  const image = useCardImage(link, "news", h.image);
  const icon = useCardImage(link, "news", image ? null : h.icon);
  const src = image ?? icon;
  if (src) return <img src={src} alt="" className={cn("size-14 shrink-0 rounded-md border bg-muted", image ? "object-cover" : "object-contain p-2")} />;
  return (
    <span aria-hidden className="grid size-14 shrink-0 place-items-center rounded-md border bg-muted text-lg font-semibold text-muted-foreground">
      {(h.source ?? h.title).charAt(0).toUpperCase()}
    </span>
  );
}

export function NewsCard({ link, body }: { link: FrameLink; body: Record<string, unknown> }) {
  const list = headlines(body);
  const title = str(body.title);
  return (
    <div className="rounded-lg border bg-card p-3 text-[13px]" data-card="news">
      <div className="text-[10px] tracking-wide text-muted-foreground uppercase">News</div>
      {title && <div className="font-semibold">{title}</div>}
      {list.length === 0 ? (
        <p className="mt-1 text-muted-foreground">No headlines.</p>
      ) : (
        <ul className="mt-2 grid gap-2">
          {list.map((h, i) => {
            const at = h.time ? Date.parse(h.time) : NaN;
            return (
              <li key={i}>
                <a
                  href={h.url}
                  onClick={(e) => {
                    e.preventDefault();
                    openExternal(h.url);
                  }}
                  className="flex items-start gap-3 rounded-md border p-2 hover:border-info/50"
                >
                  <Thumb link={link} h={h} />
                  <span className="min-w-0 flex-1">
                    <span className="line-clamp-2 font-medium text-foreground">{h.title}</span>
                    <span className="mt-0.5 block truncate text-xs text-muted-foreground">
                      {[h.source, Number.isFinite(at) ? `${ago(at)} ago`.replace("now ago", "just now") : undefined].filter(Boolean).join(" · ")}
                    </span>
                  </span>
                </a>
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}
