// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// PARITY-AUDIT-1 #9: the board card's waiting chip and runner badge (Kotlin WaitingCard, RunnerBadge). Both sit in the card's
// badge row and read state the app already holds: the ticket's `runner` and `sessionId` (board list), the session's `waiting`
// flag and state (sessions store), and the paired runners (`runner_admin list`, launcher store). Nothing is stored here.
import { useEffect, useState } from "react";
import { useStore } from "zustand";
import type { BoardTicket, WaitingView } from "@infantry/client";
import { Chip } from "@infantry/ui";
import { useDaemon } from "@/daemon/daemon";
import { launcherStore } from "@/components/dialogs/new-agent-dialog";

const minutes = (ms: number) => Math.floor(Math.max(0, ms) / 60_000);

/** `active, waiting 3m: <reason>`, or `wait over 2m ago? <reason>` (expired) once the flag stopped counting. */
export function waitingLabel(w: WaitingView, now: number): { text: string; expired: boolean } {
  const expiresAt = Number(w.expiresAt ?? Infinity);
  const expired = now > expiresAt;
  const text = expired ? `wait over ${minutes(now - Number(w.untilAt ?? expiresAt))}m ago? ${w.reason}` : `active, waiting ${minutes(now - Number(w.since))}m: ${w.reason}`;
  return { text, expired };
}

export type RunnerHealth = "up" | "degraded" | "offline";

/**
 * The badge for a ticket pinned to a runner, or null on this machine (no pin, `local`). `any` lets the pool pick, so it is
 * up. A named runner is offline when it is not paired, degraded when the ticket's session there failed or was orphaned.
 */
export function runnerBadgeOf(t: Pick<BoardTicket, "runner">, paired: string[], sessionState?: string | null): { runner: string; health: RunnerHealth } | null {
  const runner = t.runner?.trim();
  if (!runner || runner === "local") return null;
  if (runner !== "any" && !paired.includes(runner)) return { runner, health: "offline" };
  if (sessionState === "FAILED" || sessionState === "ORPHANED") return { runner, health: "degraded" };
  return { runner, health: "up" };
}

const HEALTH_TONE = { up: "green", degraded: "amber", offline: "red" } as const;

export function RunnerBadgeView({ runner, health }: { runner: string; health: RunnerHealth }) {
  return (
    <span title={`Runner ${runner}: ${health}`} data-runner-health={health}>
      <Chip tone={HEALTH_TONE[health]}>● {runner}</Chip>
    </span>
  );
}

export function WaitingChipView({ w, now }: { w: WaitingView; now: number }) {
  const { text, expired } = waitingLabel(w, now);
  return (
    <span title={text} data-waiting={expired ? "expired" : "active"} className="min-w-0">
      <Chip tone={expired ? "red" : "blue"} className="max-w-[14rem] truncate">{text}</Chip>
    </span>
  );
}

/** Re-reads the clock every 30 s so the age and the expiry stay true. */
function useNow(on: boolean): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (!on) return;
    setNow(Date.now());
    const h = setInterval(() => setNow(Date.now()), 30_000);
    return () => clearInterval(h);
  }, [on]);
  return now;
}

/** The two chips of one card, each only when it applies. */
export function CardStateChips({ t }: { t: BoardTicket }) {
  const rec = useDaemon((s) => (t.sessionId ? s.sessions[t.sessionId]?.record : undefined));
  const paired = useStore(launcherStore(), (s) => s.runners);
  const waiting = t.status === "in_progress" ? rec?.waiting : null;
  const now = useNow(!!waiting);
  const badge = runnerBadgeOf(t, paired, rec?.state);
  return (
    <>
      {waiting && <WaitingChipView w={waiting} now={now} />}
      {badge && <RunnerBadgeView {...badge} />}
    </>
  );
}
