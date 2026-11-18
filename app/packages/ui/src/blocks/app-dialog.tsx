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
  wide?: boolean;
  children: ReactNode;
}) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className={cn("max-h-[85vh] gap-0 overflow-hidden p-0", wide ? "sm:max-w-3xl" : "sm:max-w-md")}>
        <DialogHeader className="border-b p-4">
          <DialogTitle>{title}</DialogTitle>
          {description && <DialogDescription>{description}</DialogDescription>}
        </DialogHeader>
        <div className="max-h-[60vh] overflow-y-auto p-4">{children}</div>
        {footer && <DialogFooter className="m-0 border-t p-3">{footer}</DialogFooter>}
      </DialogContent>
    </Dialog>
  );
}
