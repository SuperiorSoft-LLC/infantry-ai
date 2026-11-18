// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Bordered card used across panels and tabs (one card look everywhere).
import type { ReactNode } from "react";
import { cn } from "../lib/utils";

export function PanelCard({
  title,
  action,
  children,
  className,
  bodyClassName,
}: {
  title?: ReactNode;
  action?: ReactNode;
  children: ReactNode;
  className?: string;
  bodyClassName?: string;
}) {
  return (
    <section className={cn("rounded-lg border bg-card text-card-foreground", className)}>
      {(title || action) && (
        <header className="flex items-center justify-between gap-3 px-3 pt-3">
          {title && <h3 className="text-[15px] font-semibold">{title}</h3>}
          {action}
        </header>
      )}
      <div className={cn("p-3", bodyClassName)}>{children}</div>
    </section>
  );
}

export function SectionLabel({ children, action }: { children: ReactNode; action?: ReactNode }) {
  return (
    <div className="flex items-center justify-between gap-3 px-1 py-2 text-xs text-muted-foreground">
      <span>{children}</span>
      {action}
    </div>
  );
}
