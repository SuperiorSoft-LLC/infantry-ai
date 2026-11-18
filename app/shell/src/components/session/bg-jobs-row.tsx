// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The session's background jobs (the record's `bgJobs`, after the Kotlin background-jobs row): label, pid and how long
// it has run; hidden when there are none. The Run tab's processes panel stops them.
import { Cog } from "lucide-react";
import { useDaemon } from "@/daemon/daemon";

const since = (startedAt: bigint | number) => {
  const s = Math.max(0, Math.round((Date.now() - Number(startedAt)) / 1000));
  return s < 60 ? `${s}s` : s < 3600 ? `${Math.round(s / 60)}m` : `${Math.round(s / 3600)}h`;
};

export function BgJobsRow({ sessionId }: { sessionId: string }) {
  const jobs = useDaemon((s) => s.sessions[sessionId]?.record.bgJobs);
  if (!jobs?.length) return null;
  return (
    <div className="flex flex-wrap items-center gap-1.5 px-4 pt-2 text-[11px] text-muted-foreground" aria-label="Background jobs">
      <Cog className="size-3" />
      <span>Background:</span>
      {jobs.map((j) => (
        <span key={j.id} className="rounded-full border px-1.5 font-mono" title={j.logPath ?? undefined}>
          {j.label || j.id} · pid {String(j.pid)} · {since(j.startedAt)}
        </span>
      ))}
    </div>
  );
}
