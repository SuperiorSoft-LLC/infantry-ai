// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Icon-only button with a tooltip and an accessible label. Every toolbar uses this.
import type { ComponentProps, ReactNode } from "react";
import { Button } from "../components/button";
import { Tooltip, TooltipContent, TooltipTrigger } from "../components/tooltip";

export function IconButton({
  label,
  children,
  size = "icon-sm",
  variant = "ghost",
  ...props
}: { label: string; children: ReactNode } & Omit<ComponentProps<typeof Button>, "children">) {
  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <Button aria-label={label} size={size} variant={variant} {...props}>
          {children}
        </Button>
      </TooltipTrigger>
      <TooltipContent>{label}</TooltipContent>
    </Tooltip>
  );
}
