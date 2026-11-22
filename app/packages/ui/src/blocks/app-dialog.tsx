// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The ONE modal. Every dialog in the app renders through this wrapper around shadcn Dialog.
import type { ReactNode } from "react";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "../components/dialog";
import { cn } from "../lib/utils";

/** Width tiers. default is at least the chat feed's width (the centre column), full width minus gutters on a phone. */
export const DIALOG_WIDTHS = {
  default: "w-[min(760px,calc(100vw-2rem))] sm:max-w-none",
  wide: "w-[min(960px,calc(100vw-2rem))] sm:max-w-none",
  full: "w-[min(1400px,calc(100vw-2rem))] sm:max-w-none",
} as const;

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
  /** "screen": the whole window minus a 1rem gutter, full height (the Board). true: a roomy form dialog; "fill": grows with the window (min(1100px, 92vw) by min(85vh, 820px)), for settings-like content. */
  wide?: boolean | "fill" | "image" | "full" | "screen";
  children: ReactNode;
}) {
  const fill = wide === "fill";
  const image = wide === "image";
  const screen = wide === "screen";
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent
        className={cn(
          "flex max-h-[85vh] flex-col gap-0 overflow-hidden p-0",
          screen
            ? "h-[calc(100dvh-2rem)] max-h-none w-[calc(100vw-2rem)] sm:max-w-none"
            : fill
            ? "h-[min(85vh,820px)] w-[min(1100px,92vw)] min-w-[min(480px,92vw)] sm:max-w-none"
            : image
              ? "h-auto max-h-[88vh] w-fit max-w-[92vw] min-w-[min(480px,92vw)] sm:max-w-[92vw] max-sm:h-dvh max-sm:max-h-none max-sm:w-screen max-sm:max-w-none max-sm:rounded-none"
              : DIALOG_WIDTHS[wide === "full" ? "full" : wide ? "wide" : "default"],
        )}
      >
        <DialogHeader className="border-b p-4 pr-12">
          <DialogTitle>{title}</DialogTitle>
          {description && (
            <DialogDescription className="truncate" title={description}>
              {description}
            </DialogDescription>
          )}
        </DialogHeader>
        <div className="min-h-0 flex-1 overflow-x-hidden overflow-y-auto p-4">{children}</div>
        {footer && <DialogFooter className="m-0 border-t p-3">{footer}</DialogFooter>}
      </DialogContent>
    </Dialog>
  );
}
