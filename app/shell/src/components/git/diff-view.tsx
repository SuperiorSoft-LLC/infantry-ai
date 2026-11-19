// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The diff viewer: unified or side by side, line numbers, syntax highlighting. Honest states: loading, unknown
// (with the daemon's reason), binary, truncated, no difference.
import { memo, useMemo } from "react";
import { parseUnifiedDiff, splitRows, type GitPatch, type PatchLine } from "@infantry/client";
import { cn } from "@infantry/ui";
import { langOf, tokenClass, tokenize } from "./highlight";

export type DiffMode = "unified" | "split";

const bg: Record<string, string> = {
  add: "bg-ok/10",
  del: "bg-danger/10",
  context: "",
  hunk: "bg-info/10 text-info",
  meta: "text-muted-foreground",
};

const Code = memo(function Code({ text, lang }: { text: string; lang: string | null }) {
  return (
    <>
      {tokenize(text, lang).map((t, i) => (
        <span key={i} className={tokenClass[t.kind]}>
          {t.text}
        </span>
      ))}
    </>
  );
});

function No({ n }: { n: number | null }) {
  return <span className="inline-block w-10 shrink-0 select-none pr-2 text-right text-muted-foreground/70">{n ?? ""}</span>;
}

function Sign({ kind }: { kind: PatchLine["kind"] }) {
  const s = kind === "add" ? "+" : kind === "del" ? "-" : " ";
  return <span className={cn("inline-block w-4 shrink-0 select-none", kind === "add" && "text-ok", kind === "del" && "text-danger")}>{s}</span>;
}

function Unified({ lines, lang }: { lines: PatchLine[]; lang: string | null }) {
  return (
    <div className="min-w-max">
      {lines.map((l, i) =>
        l.kind === "hunk" || l.kind === "meta" ? (
          <div key={i} className={cn("whitespace-pre px-2", bg[l.kind])}>
            {l.text}
          </div>
        ) : (
          <div key={i} className={cn("flex whitespace-pre", bg[l.kind])}>
            <No n={l.oldNo} />
            <No n={l.newNo} />
            <Sign kind={l.kind} />
            <span>
              <Code text={l.text} lang={lang} />
            </span>
          </div>
        ),
      )}
    </div>
  );
}

function Half({ line, lang }: { line: PatchLine | null; lang: string | null }) {
  if (!line) return <div className="min-w-0 flex-1 bg-raised/40" />;
  return (
    <div className={cn("flex min-w-0 flex-1 overflow-hidden whitespace-pre", bg[line.kind])}>
      <No n={line.kind === "add" ? line.newNo : line.oldNo ?? line.newNo} />
      <Sign kind={line.kind} />
      <span className="truncate">
        <Code text={line.text} lang={lang} />
      </span>
    </div>
  );
}

function Split({ lines, lang }: { lines: PatchLine[]; lang: string | null }) {
  const rows = useMemo(() => splitRows(lines), [lines]);
  return (
    <div>
      {rows.map((r, i) =>
        r.full ? (
          r.full.kind === "meta" ? null : (
            <div key={i} className={cn("whitespace-pre px-2", bg[r.full.kind])}>
              {r.full.text}
            </div>
          )
        ) : (
          <div key={i} className="flex divide-x divide-border">
            <Half line={r.left} lang={lang} />
            <Half line={r.right?.kind === "context" ? { ...r.right, oldNo: r.right.newNo } : r.right} lang={lang} />
          </div>
        ),
      )}
    </div>
  );
}

export function DiffView({ patch, loading, mode, wrapClass }: { patch: GitPatch | null; loading: boolean; mode: DiffMode; wrapClass?: string }) {
  const lines = useMemo(() => (patch?.patch ? parseUnifiedDiff(patch.patch) : []), [patch?.patch]);
  const lang = patch ? langOf(patch.path) : null;
  const note = (text: string, tone = "text-muted-foreground") => <div className={cn("p-4 text-xs", tone)}>{text}</div>;
  if (loading && !patch) return note("Loading diff…");
  if (!patch) return note("Pick a changed file to see its diff.");
  if (!patch.available) return note(`Diff unknown: ${patch.reason || "the daemon could not read it"}`, "text-warn");
  if (patch.binary) return note("Binary file: no text diff.");
  if (!patch.patch.trim()) return note("No difference.");
  return (
    <div className={cn("font-mono text-xs leading-5", wrapClass)}>
      {patch.truncated && <div className="border-b bg-warn/10 px-3 py-1 text-warn">Diff cut by the daemon (too large to show whole).</div>}
      {mode === "split" ? <Split lines={lines} lang={lang} /> : <Unified lines={lines} lang={lang} />}
    </div>
  );
}
