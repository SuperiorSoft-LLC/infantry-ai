// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Custom titlebar window buttons (Tauri decorations are off). In a plain browser they render but do nothing.
import { Minus, Square, X } from "lucide-react";
import { isTauri } from "@tauri-apps/api/core";
import { getCurrentWindow } from "@tauri-apps/api/window";
import { cn } from "@infantry/ui";

type WindowAction = "minimize" | "toggleMaximize" | "close";

function run(action: WindowAction) {
  if (!isTauri()) return;
  const win = getCurrentWindow();
  void win[action]();
}

function WinButton({ label, action, danger, children }: { label: string; action: WindowAction; danger?: boolean; children: React.ReactNode }) {
  return (
    <button
      type="button"
      aria-label={label}
      title={label}
      onClick={() => run(action)}
      className={cn(
        "grid h-full w-11 place-items-center text-muted-foreground transition-colors hover:bg-accent hover:text-foreground [&_svg]:size-4",
        danger && "hover:bg-danger hover:text-white",
      )}
    >
      {children}
    </button>
  );
}

export function WindowControls() {
  return (
    <div className="flex h-full items-stretch">
      <WinButton label="Minimize" action="minimize">
        <Minus />
      </WinButton>
      <WinButton label="Maximize" action="toggleMaximize">
        <Square className="size-3.5!" />
      </WinButton>
      <WinButton label="Close" action="close" danger>
        <X />
      </WinButton>
    </div>
  );
}
