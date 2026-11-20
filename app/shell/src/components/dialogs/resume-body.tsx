// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// RUST-PERF-1: resume an existing Claude session. Pick a project folder; its transcripts
// (`~/.claude/projects/<slug>/*.jsonl`, newest first) are listed as cards; the picked one runs `claude --resume <id>`.
// A session must never run in two apps at once: one already running here is marked and refused.
import { useEffect, useState } from "react";
import { FolderOpen, History } from "lucide-react";
import { Button, EmptyState, ListRow, cn } from "@infantry/ui";
import type { ResumableSession } from "@infantry/client";
import { useDaemon } from "@/daemon/daemon";
import { host } from "@/host";
import { ago } from "@/daemon/agents";

/** Shown only where the embedded runner runs (dialog-host.tsx), so host.embedded is there. */
export function ResumeBody({ folder, onFolder, picked, onPick }: { folder: string | null; onFolder: (f: string) => void; picked: string | null; onPick: (id: string | null) => void }) {
  const [list, setList] = useState<ResumableSession[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const current = useDaemon((s) => s.projects[0]?.directory ?? null);
  const dir = folder ?? current;
  const runner = host.embedded;

  useEffect(() => {
    if (!dir || !runner) return;
    setList(null);
    runner.resumable(dir).then(setList, (e) => setError(String(e)));
  }, [dir, runner]);

  const choose = async () => {
    const f = await host.pickFolder("Project folder");
    if (!f || !runner) return;
    try {
      await runner.setProject(f);
      onPick(null);
      onFolder(f);
    } catch (e) {
      setError(String(e));
    }
  };

  return (
    <div className="space-y-3">
      <div className="flex items-center gap-2 rounded-lg border bg-card p-3">
        <FolderOpen className="size-4 shrink-0 text-muted-foreground" />
        <span className="min-w-0 flex-1 truncate font-mono text-xs">{dir ?? "No project folder picked"}</span>
        <Button variant="outline" size="sm" onClick={() => void choose()}>
          Choose folder...
        </Button>
      </div>
      {error && <div className="text-xs text-danger">{error}</div>}
      {dir && list && list.length === 0 && <EmptyState icon={<History className="size-6" />} title="No Claude sessions here" hint="Claude keeps none for this folder yet." />}
      {list && list.length > 0 && (
        <div role="listbox" aria-label="Sessions to resume" className="space-y-2">
          {list.map((r) => (
            <div
              key={r.id}
              role="option"
              aria-selected={picked === r.id}
              aria-disabled={r.running}
              onClick={() => !r.running && onPick(r.id)}
              className={cn("cursor-pointer rounded-lg", picked === r.id && "ring-2 ring-ring", r.running && "cursor-not-allowed opacity-60")}
            >
              <ListRow
                row={{ id: r.id, title: r.title, subtitle: r.id, meta: `${ago(r.modified)} · ${Math.max(1, Math.round(r.bytes / 1024))} KB`, badge: r.running ? "running here" : undefined, tone: "green" }}
                icon={<History className="size-4" />}
              />
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
