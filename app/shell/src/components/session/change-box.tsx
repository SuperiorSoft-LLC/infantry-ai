// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The change box (Kotlin ChangeBox + DiffLine): one file write, drawn in the chat feed and in the Activity box from
// the same data. Header: icon, path, created / edited, +N -M. Body: numbered diff rows. Collapsed to the preview with a
// fade, "+N more lines" and "View all (N lines)"; expanded, "Collapse" folds it back.
import { useState } from "react";
import { FilePlus2, Pencil } from "lucide-react";
import { cn } from "@infantry/ui";
import { writeToDiff, type DiffLine, type FileWrite } from "@infantry/client";

/** Rows a collapsed change box shows (Kotlin `ChangeBox.PREVIEW_LINES`). */
export const CHANGE_BOX_PREVIEW_LINES = 12;

export function hiddenCount(rows: number, preview = CHANGE_BOX_PREVIEW_LINES): number {
  return Math.max(0, rows - preview);
}
export function moreLabel(rows: number, preview = CHANGE_BOX_PREVIEW_LINES): string {
  const n = hiddenCount(rows, preview);
  return `+${n} more ${n === 1 ? "line" : "lines"}`;
}

export function DiffRow({ line, oldGutter = true }: { line: DiffLine; oldGutter?: boolean }) {
  const sign = line.kind === "add" ? "+" : line.kind === "del" ? "-" : line.kind === "ctx" ? " " : "";
  return (
    <div
      data-testid={`diff-line-${line.kind}`}
      className={cn(
        "flex min-w-max font-mono text-xs leading-5",
        line.kind === "add" && "bg-diff-add/70 text-foreground",
        line.kind === "del" && "bg-diff-del/70 text-foreground",
        line.kind === "hunk" && "bg-muted text-muted-foreground",
      )}
    >
      {oldGutter && <span className="w-11 shrink-0 pr-1.5 text-right text-muted-foreground select-none">{line.oldNo ?? ""}</span>}
      <span className="w-11 shrink-0 pr-1.5 text-right text-muted-foreground select-none">{line.newNo ?? ""}</span>
      <span className="w-4 shrink-0 text-muted-foreground select-none">{sign}</span>
      <span className="pr-4 whitespace-pre">{line.text || " "}</span>
    </div>
  );
}

export interface ChangeBoxProps {
  path: string;
  created: boolean;
  added: number;
  removed: number;
  lines: DiffLine[];
  truncated?: boolean;
  /** Rows shown before "View all" (a setting; default [CHANGE_BOX_PREVIEW_LINES]). */
  previewLines?: number;
}

export function ChangeBox({ path, created, added, removed, lines, truncated, previewLines = CHANGE_BOX_PREVIEW_LINES }: ChangeBoxProps) {
  const [expanded, setExpanded] = useState(false);
  const collapsible = lines.length > previewLines;
  const folded = collapsible && !expanded;
  const shown = folded ? lines.slice(0, previewLines) : lines;
  return (
    <div data-testid="change-box" className="overflow-hidden rounded-lg border bg-background">
      <div className="flex items-center gap-2 border-b bg-card px-3 py-1.5 text-xs">
        {created ? <FilePlus2 aria-label="created" className="size-3.5 shrink-0 text-ok" /> : <Pencil aria-label="edited" className="size-3.5 shrink-0 text-info" />}
        <span className="min-w-0 truncate font-mono font-semibold" title={path}>
          {path}
        </span>
        <span className="shrink-0 text-muted-foreground">{created ? "created" : "edited"}</span>
        <span className="flex-1" />
        <span data-testid="change-box-counts" className="flex shrink-0 gap-1.5 font-mono font-bold">
          <span className="text-ok">+{added}</span>
          {!created && <span className="text-danger">−{removed}</span>}
        </span>
      </div>
      {truncated && <div className="px-3 py-1 text-[11px] text-muted-foreground">Cut by the daemon: the write is longer than shown.</div>}
      <div className="relative">
        <div className="overflow-x-auto py-1">
          {shown.map((l, i) => (
            <DiffRow key={i} line={l} oldGutter={!created} />
          ))}
        </div>
        {folded && <div data-testid="change-box-fade" className="pointer-events-none absolute inset-x-0 bottom-0 h-9 bg-gradient-to-b from-transparent to-background" />}
      </div>
      {collapsible && (
        <div className="flex items-center border-t bg-card px-3 py-1 text-[11px]">
          {folded && (
            <span data-testid="change-box-more" className="font-mono text-muted-foreground">
              {moreLabel(lines.length, previewLines)}
            </span>
          )}
          <span className="flex-1" />
          <button type="button" data-testid={folded ? "change-box-view-all" : "change-box-collapse"} onClick={() => setExpanded(folded)} className="rounded px-1.5 py-0.5 font-semibold text-primary hover:underline">
            {folded ? `View all (${lines.length} lines)` : "Collapse"}
          </button>
        </div>
      )}
    </div>
  );
}

/** A change box from the wire write (the Activity box has no transcript item to take it from). */
export function ChangeBoxOfWrite({ write, previewLines }: { write: FileWrite; previewLines?: number }) {
  const d = writeToDiff(write);
  const created = write.content != null && !write.patch && !(write.edits?.length);
  return <ChangeBox path={write.path} created={created} truncated={write.truncated === true} previewLines={previewLines} {...d} />;
}
