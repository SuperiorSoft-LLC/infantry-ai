// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The ONE modal. Every dialog in the app renders through this wrapper around shadcn Dialog.
import type { ReactNode } from "react";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "../components/dialog";
import { cn } from "../lib/utils";

export function AppDialog({
  open,
  onOpenChange,
  title,
  description,
  footer,
  wide,
  children,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  title: string;
  description?: string;
  footer?: ReactNode;
  /** true: a roomy form dialog; "fill": grows with the window (min(1100px, 92vw) by min(85vh, 820px)), for settings-like content. */
  wide?: boolean | "fill";
  children: ReactNode;
}) {
  const fill = wide === "fill";
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent
        className={cn(
          "flex max-h-[85vh] flex-col gap-0 overflow-hidden p-0",
          fill ? "h-[min(85vh,820px)] w-[min(1100px,92vw)] min-w-[min(480px,92vw)] sm:max-w-none" : wide ? "sm:max-w-3xl" : "sm:max-w-md",
        )}
      >
        <DialogHeader className="border-b p-4">
          <DialogTitle>{title}</DialogTitle>
          {description && <DialogDescription>{description}</DialogDescription>}
        </DialogHeader>
        <div className="min-h-0 flex-1 overflow-x-hidden overflow-y-auto p-4">{children}</div>
        {footer && <DialogFooter className="m-0 border-t p-3">{footer}</DialogFooter>}
      </DialogContent>
    </Dialog>
  );
}
