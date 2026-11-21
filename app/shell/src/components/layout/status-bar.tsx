// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Bottom status bar: connection, processes, an update the app found, keyboard hints, version.
import type { ReactNode } from "react";
import { Cpu } from "lucide-react";
import { shortcutHints } from "@infantry/client";
import { localMode } from "@/daemon/daemon";
import { useAttention } from "@/components/mail/use-mail";
import { useAppVersionLine } from "./app-version";
import { KeyHint } from "@infantry/ui";
import { ConnectionBadge } from "./connection-badge";
import { useUi } from "@/state/ui-state";
import { useRegistry } from "@/plugins/use-plugins";
import { useRuns } from "@/components/run/use-runs";

/** The `infantry.shell.footer.processesBadge` slot: shown while a plugin fills it (or before the plugin list is in). */
function ProcessesBadge() {
  const reg = useRegistry();
  const ui = useUi();
  const report = useRuns((s) => s.processes?.report);
  if (reg && !reg.fills["infantry.shell.footer.processesBadge"]) return null;
  const n = report ? report.tracked.length + report.unattributed.length : null;
  const rogue = report ? [...report.tracked, ...report.unattributed].some((p) => p.rogue) : false;
  return (
    <button type="button" className={`flex items-center gap-1 hover:underline ${rogue || report?.underPressure ? "text-danger" : "text-muted-foreground"}`} onClick={() => ui.set("sessionTab", "processes")}>
      <Cpu className="size-3" /> {n == null ? "processes" : `${n} process${n === 1 ? "" : "es"}`}
    </button>
  );
}

/** The "update available" notice the start check posted (RUST-UPD-1); opens Settings > About. */
function UpdatePill() {
  const ui = useUi();
  const note = useAttention((s) => s.notifications.find((n) => n.id.startsWith("app-update-")));
  if (!note) return null;
  return (
    <button type="button" className="hidden truncate text-ok hover:underline md:inline" onClick={() => ui.set("dialog", "settings")}>
      {note.title}
    </button>
  );
}

/**
 * `leading`: a layout's entries for the left end (the Desktop's taskbar). Then the strip is one bar: those entries on the left,
 * the status text (connection, processes, hints, version) on the right; and on a phone it is tall enough for touch targets.
 */
export function StatusBar({ leading }: { leading?: ReactNode } = {}) {
  const version = useAppVersionLine();
  const status = (
    <>
      <ConnectionBadge />
      <ProcessesBadge />
      <UpdatePill />
    </>
  );
  return (
    <footer className={`flex shrink-0 items-center gap-4 border-t bg-background px-3 text-[11px] whitespace-nowrap ${leading ? "h-6 max-sm:h-14" : "h-6"}`}>
      {leading}
      {!leading && status}
      <span className="flex-1" />
      {leading && <span className="flex min-w-0 items-center gap-3">{status}</span>}
      <span className="hidden items-center gap-3 xl:flex">
        {shortcutHints({ resume: localMode }).map((h) => (
          <KeyHint key={h}>{h}</KeyHint>
        ))}
      </span>
      <span className="hidden font-mono text-muted-foreground lg:inline">{version}</span>
    </footer>
  );
}
