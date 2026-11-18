// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The session transcript, virtualised (TanStack Virtual): only the rows in view (plus an overscan) are in the DOM, each
// measured after it renders. Rows are memoised by item identity: the client hands back the same object for an item that
// did not change, so a streaming append re-renders only the row it touched. The list sticks to the bottom while the
// reader is there and stays put once they scroll up.
import { memo, useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import { ArrowDown, MessageSquareDashed } from "lucide-react";
import { useVirtualizer } from "@tanstack/react-virtual";
import { useShallow } from "zustand/react/shallow";
import type { PendingSend, TranscriptItem } from "@infantry/client";
import { EmptyState, IconButton } from "@infantry/ui";
import type { Agent } from "@/mock/types";
import { useDaemon } from "@/daemon/daemon";
import { AgentAvatar } from "@/components/agents/agent-avatar";
import { CommsCard, ThinkingSpinner, TranscriptItemView, timeOf } from "./transcript-items";

/** Kinds that start an agent turn and so carry the avatar in the gutter. */
const startsTurn = new Set<TranscriptItem["kind"]>(["thinking", "text", "note"]);
/** First guess of a row's height before it is measured (px). */
const ESTIMATED_ROW_PX = 56;
/** Rows rendered beyond the viewport on each side. */
const OVERSCAN_ROWS = 8;
/** Distance from the bottom (px) that still counts as "at the bottom". */
const STICK_SLACK_PX = 48;

type Row = { key: string; item: TranscriptItem; pending?: undefined } | { key: string; item?: undefined; pending: PendingSend };

const ItemRow = memo(function ItemRow({ item, avatar }: { item: TranscriptItem; avatar: Agent }) {
  return (
    <div className="grid grid-cols-[2.5rem_1fr] gap-2">
      <div>{startsTurn.has(item.kind) ? <AgentAvatar agent={avatar} size="sm" showStatus={false} /> : null}</div>
      <div className="min-w-0">
        <TranscriptItemView item={item} />
      </div>
    </div>
  );
});

const PendingRow = memo(function PendingRow({ p, onRetry }: { p: PendingSend; onRetry: (clientId: string) => void }) {
  return (
    <div className="grid grid-cols-[2.5rem_1fr] gap-2">
      <div />
      <div className="min-w-0">
        <CommsCard time={timeOf(p.at)} owner body={{ paragraphs: [p.text] }} sending={{ state: p.state, reason: p.reason }} onRetry={() => onRetry(p.clientId)} />
      </div>
    </div>
  );
});

function Elapsed({ since }: { since: number }) {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(t);
  }, []);
  const s = Math.max(0, Math.round((now - since) / 1000));
  return <>{s < 60 ? `${s}s` : `${Math.floor(s / 60)}m ${s % 60}s`}</>;
}

export function Transcript({ agent, sessionId }: { agent: Agent; sessionId: string }) {
  const items = useDaemon((s) => s.sessions[sessionId]?.items);
  const since = useDaemon((s) => s.sessions[sessionId]?.status?.runActivity?.turnStartedAt ?? s.sessions[sessionId]?.record.lastHeartbeat);
  const pending = useDaemon(useShallow((s) => Object.values(s.pending).filter((p) => p.sessionId === sessionId && p.state !== "accepted")));
  const retry = useDaemon((s) => s.retry);
  // The avatar only reads hue and role: keep one object per session so rows do not re-render on every status change.
  const avatar = useMemo(() => agent, [agent.id, agent.hue, agent.roleId]); // eslint-disable-line react-hooks/exhaustive-deps
  const rows = useMemo<Row[]>(
    () => [...(items ?? []).map((item) => ({ key: item.id, item })), ...pending.map((p) => ({ key: p.clientId, pending: p }))],
    [items, pending],
  );

  const scrollRef = useRef<HTMLDivElement>(null);
  const stick = useRef(true);
  const [away, setAway] = useState(false);
  const v = useVirtualizer({
    count: rows.length,
    getScrollElement: () => scrollRef.current,
    estimateSize: () => ESTIMATED_ROW_PX,
    overscan: OVERSCAN_ROWS,
    getItemKey: (i) => rows[i].key,
  });

  const onScroll = useCallback(() => {
    const el = scrollRef.current;
    if (!el) return;
    const atBottom = el.scrollHeight - el.scrollTop - el.clientHeight <= STICK_SLACK_PX;
    stick.current = atBottom;
    setAway(!atBottom);
  }, []);

  const toBottom = useCallback(() => {
    stick.current = true;
    setAway(false);
    if (rows.length > 0) v.scrollToIndex(rows.length - 1, { align: "end" });
  }, [rows.length, v]);

  // A new session starts at its newest line.
  useLayoutEffect(() => {
    stick.current = true;
    setAway(false);
  }, [sessionId]);

  // New rows, or a row that grew after measuring: follow the end while the reader is there.
  const total = v.getTotalSize();
  useLayoutEffect(() => {
    const el = scrollRef.current;
    if (el && stick.current) el.scrollTop = el.scrollHeight;
  }, [total, rows.length, sessionId]);

  if (rows.length === 0) {
    return (
      <div className="grid flex-1 place-items-center p-6">
        <EmptyState icon={<MessageSquareDashed className="size-7" />} title="No output yet" hint="Send a prompt to start this session." />
      </div>
    );
  }
  return (
    <div className="relative flex min-h-0 flex-1 flex-col">
      <div ref={scrollRef} onScroll={onScroll} className="min-h-0 flex-1 overflow-y-auto [overflow-anchor:none]" data-testid="transcript-scroll">
        <ol className="relative px-4 py-3" aria-label="Transcript" style={{ height: total + 24 }}>
          {v.getVirtualItems().map((vi) => {
            const row = rows[vi.index];
            return (
              <li
                key={vi.key}
                data-index={vi.index}
                ref={v.measureElement}
                className="absolute top-3 right-4 left-4 pb-3"
                style={{ transform: `translateY(${vi.start}px)` }}
              >
                {row.item ? <ItemRow item={row.item} avatar={avatar} /> : <PendingRow p={row.pending} onRetry={retry} />}
              </li>
            );
          })}
        </ol>
        {agent.status === "working" && since != null && (
          <div className="px-4 pb-3 pl-16">
            <ThinkingSpinner label={agent.statusLine} elapsed={<Elapsed since={since} />} />
          </div>
        )}
      </div>
      {away && (
        <IconButton label="Jump to latest" variant="outline" className="absolute right-5 bottom-16 rounded-full bg-card shadow" onClick={toBottom}>
          <ArrowDown />
        </IconButton>
      )}
    </div>
  );
}
