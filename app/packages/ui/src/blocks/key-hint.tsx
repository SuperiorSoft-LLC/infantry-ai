// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
import { cn } from "../lib/utils";

export function KeyHint({ children, className }: { children: string; className?: string }) {
  return <kbd className={cn("font-mono text-[10px] text-muted-foreground/80", className)}>{children}</kbd>;
}
