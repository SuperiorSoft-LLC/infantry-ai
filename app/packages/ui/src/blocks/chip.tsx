// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Outlined chip used for every status/provider/model label. Tone picks the colour; padding is built in
// so a chip never touches neighbouring text (parents also lay chips out with gap).
import type { ReactNode } from "react";
import { cn } from "../lib/utils";
import type { Tone } from "../types";

const tones: Record<Tone, string> = {
  neutral: "border-border text-muted-foreground bg-transparent",
  red: "border-danger/60 text-danger bg-danger/10",
  green: "border-ok/50 text-ok bg-ok/10",
  blue: "border-info/50 text-info bg-info/10",
  amber: "border-warn/60 text-warn bg-warn/10",
};

export function Chip({
  tone = "neutral",
  mono,
  className,
  children,
}: {
  tone?: Tone;
  mono?: boolean;
  className?: string;
  children: ReactNode;
}) {
  return (
    <span
      className={cn(
        "inline-flex h-6 shrink-0 items-center gap-1 rounded-md border px-2 text-xs leading-none whitespace-nowrap",
        mono && "font-mono text-[11px]",
        tones[tone],
        className,
      )}
    >
      {children}
    </span>
  );
}
