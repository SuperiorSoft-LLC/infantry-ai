// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
import { cn } from "../lib/utils";
import type { StatusKind as AgentStatus } from "../types";

const colour: Record<AgentStatus, string> = {
  idle: "bg-info",
  working: "bg-ok",
  waiting: "bg-muted-foreground",
  "needs-input": "bg-warn",
  review: "bg-info",
  done: "bg-ok",
  error: "bg-danger",
};

export function StatusDot({ status, className, pulse }: { status: AgentStatus; className?: string; pulse?: boolean }) {
  return (
    <span
      role="img"
      aria-label={status}
      className={cn("inline-block size-2 shrink-0 rounded-full", colour[status], pulse && (status === "needs-input" ? "wait-pulse" : "busy-pulse"), className)}
    />
  );
}
