// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Draws chat markdown (@infantry/client parseMarkdown): headings, code blocks with a language label and Copy, lists,
// quotes, tables with sideways scroll, `card:<type>` fences as chat cards (chat-cards.tsx). No raw HTML; links open
// outside the app.
import { memo, useMemo, useState, type ReactNode } from "react";
import { Check, Copy } from "lucide-react";
import { cardOf, foldCards, parseMarkdown, type Block, type Span } from "@infantry/client";
import { CardUpdateLine, ChatCardView } from "./chat-cards";
import { cn } from "@infantry/ui";
import { ExternalLink } from "@/components/external-link";

function Spans({ spans }: { spans: Span[] }): ReactNode {
  return spans.map((s, i) => {
    switch (s.kind) {
      case "text":
        return <span key={i}>{s.text}</span>;
      case "code":
        return (
          <code key={i} className="rounded bg-muted px-1 py-0.5 font-mono text-[0.9em]">
            {s.text}
          </code>
        );
      case "bold":
        return (
          <strong key={i}>
            <Spans spans={s.spans} />
          </strong>
        );
      case "italic":
        return (
          <em key={i}>
            <Spans spans={s.spans} />
          </em>
        );
      case "link":
        return (
          <ExternalLink key={i} href={s.href} className="text-info underline underline-offset-2">
            <Spans spans={s.spans} />
          </ExternalLink>
        );
    }
  });
}

function CodeBlock({ lang, text }: { lang: string; text: string }) {
  const [copied, setCopied] = useState(false);
  const copy = () =>
    void navigator.clipboard?.writeText(text).then(() => {
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    });
  return (
    <div className="overflow-hidden rounded-md border bg-muted/40">
      <div className="flex items-center gap-2 border-b px-2 py-1 text-[11px] text-muted-foreground">
        <span className="font-mono">{lang.split(/\s+/)[0] || "text"}</span>
        <span className="flex-1" />
        <button type="button" onClick={copy} className="flex items-center gap-1 hover:text-foreground" aria-label="Copy code">
          {copied ? <Check className="size-3" /> : <Copy className="size-3" />} {copied ? "Copied" : "Copy"}
        </button>
      </div>
      <pre className="overflow-x-auto p-2 font-mono text-[12px] leading-5">
        <code>{text}</code>
      </pre>
    </div>
  );
}

function BlockView({ b }: { b: Block }): ReactNode {
  switch (b.kind) {
    case "heading":
      return (
        <div role="heading" aria-level={b.level} className={cn("font-semibold", b.level === 1 ? "text-base" : b.level === 2 ? "text-[14.5px]" : "text-[13.5px]")}>
          <Spans spans={b.spans} />
        </div>
      );
    case "code":
      return <CodeBlock lang={b.lang} text={b.text} />;
    case "list": {
      const items = b.items.map((it, i) => (
        <li key={i} className="whitespace-pre-wrap">
          <Spans spans={it} />
        </li>
      ));
      return b.ordered ? (
        <ol start={b.start} className="list-decimal space-y-0.5 pl-5">
          {items}
        </ol>
      ) : (
        <ul className="list-disc space-y-0.5 pl-5">{items}</ul>
      );
    }
    case "quote":
      return (
        <blockquote className="space-y-2 border-l-2 pl-3 text-muted-foreground">
          {b.blocks.map((x, i) => (
            <BlockView key={i} b={x} />
          ))}
        </blockquote>
      );
    case "table":
      return (
        <div className="overflow-x-auto">
          <table className="border-collapse text-[12.5px]">
            <thead>
              <tr>
                {b.head.map((c, i) => (
                  <th key={i} className="border px-2 py-1 font-semibold" style={{ textAlign: b.align[i] ?? "left" }}>
                    <Spans spans={c} />
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {b.rows.map((r, ri) => (
                <tr key={ri}>
                  {r.map((c, i) => (
                    <td key={i} className="border px-2 py-1" style={{ textAlign: b.align[i] ?? "left" }}>
                      <Spans spans={c} />
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      );
    case "paragraph":
      return (
        <p className="whitespace-pre-wrap">
          <Spans spans={b.spans} />
        </p>
      );
  }
}

/** One message's markdown. Memoised: transcript rows re-render often, the text rarely changes. */
export const Markdown = memo(function Markdown({ text }: { text: string }) {
  const { blocks, cards } = useMemo(() => {
    const blocks = parseMarkdown(text);
    return { blocks, cards: foldCards(blocks.map((b) => (b.kind === "code" ? cardOf(b.lang, b.text, b.open) : null))) };
  }, [text]);
  return (
    <>
      {blocks.map((b, i) => {
        const c = cards[i];
        if (c === "update") return <CardUpdateLine key={i} type={b.kind === "code" ? b.lang.split(/\s+/)[0].slice(5) : ""} />;
        if (c) return <ChatCardView key={i} card={c} />;
        return <BlockView key={i} b={b} />;
      })}
    </>
  );
});
