// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The round buttons of the chat (Kotlin FloatingChip.kt and ChatSurface ComposerRound). FloatingChip floats over the
// transcript (Activity, Search, Expand, Minimize, Restore): a 36px disc on the panel tone with a thin border and a muted
// glyph that brightens on hover, inside a 48px hit area. ComposerRound is a composer button: a 36px disc in the raised
// tone (or filled red for End session, with a ring), same 48px hit area, dimmed when disabled. Both carry the usual
// tooltip, "label (shortcut)".
import type { ReactNode } from "react";
import { Tooltip, TooltipContent, TooltipTrigger, cn } from "@infantry/ui";

function Tip({ label, shortcut, children }: { label: string; shortcut?: string; children: ReactNode }) {
  return (
    <Tooltip>
      <TooltipTrigger asChild>{children}</TooltipTrigger>
      <TooltipContent>{shortcut ? `${label} (${shortcut})` : label}</TooltipContent>
    </Tooltip>
  );
}

export function FloatingChip({ label, shortcut, onClick, children }: { label: string; shortcut?: string; onClick: () => void; children: ReactNode }) {
  return (
    <Tip label={label} shortcut={shortcut}>
      <button type="button" aria-label={label} onClick={onClick} className="group/chip grid size-12 shrink-0 cursor-pointer place-items-center outline-none">
        <span className="grid size-9 place-items-center rounded-full border bg-card text-muted-foreground transition-colors group-hover/chip:bg-accent group-hover/chip:text-foreground group-focus-visible/chip:ring-2 group-focus-visible/chip:ring-ring [&_svg]:size-[18px]">
          {children}
        </span>
      </button>
    </Tip>
  );
}

export function ComposerRound({
  label,
  shortcut,
  onClick,
  disabled = false,
  tone = "plain",
  children,
}: {
  label: string;
  shortcut?: string;
  onClick: () => void;
  disabled?: boolean;
  /** plain: text glyph; danger: red glyph; filled: red disc, white glyph; accent: the brand glyph (Send). */
  tone?: "plain" | "danger" | "filled" | "accent";
  children: ReactNode;
}) {
  return (
    <Tip label={label} shortcut={shortcut}>
      {/* A disabled button still shows its tooltip (aria-disabled, not disabled), as the Kotlin one does. */}
      <button
        type="button"
        aria-label={label}
        aria-disabled={disabled}
        onClick={() => !disabled && onClick()}
        className={cn("group/round grid size-12 shrink-0 place-items-center outline-none", disabled ? "cursor-default" : "cursor-pointer")}
      >
        <span
          className={cn(
            "grid size-9 place-items-center rounded-full transition-colors group-focus-visible/round:ring-2 group-focus-visible/round:ring-ring [&_svg]:size-[18px]",
            tone === "filled" ? "bg-danger text-white ring-2 ring-accent" : "bg-accent",
            tone === "danger" && "text-danger",
            tone === "accent" && "text-primary",
            tone === "plain" && "text-foreground",
            disabled ? "opacity-45" : tone === "filled" ? "group-hover/round:bg-danger/85" : "group-hover/round:bg-muted",
          )}
        >
          {children}
        </span>
      </button>
    </Tip>
  );
}
