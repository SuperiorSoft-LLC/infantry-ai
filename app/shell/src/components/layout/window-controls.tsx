// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Custom titlebar window buttons (Tauri decorations are off). Only the desktop app has them (HC-1 host.window): a browser
// and a phone draw their own frame, so nothing renders there (UI-WINCTRL-WEB-1: no dead buttons widening a phone page).
import { Minus, Square, X } from "lucide-react";
import type { HostWindow } from "@infantry/client";
import { cn } from "@infantry/ui";
import { host } from "@/host";

type WindowAction = keyof HostWindow;

function WinButton({ frame, label, action, danger, children }: { frame: HostWindow; label: string; action: WindowAction; danger?: boolean; children: React.ReactNode }) {
  return (
    <button
      type="button"
      aria-label={label}
      title={label}
      onClick={() => frame[action]()}
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
  const frame = host.window;
  if (!frame) return null;
  return (
    <div className="flex h-full items-stretch">
      <WinButton frame={frame} label="Minimize" action="minimize">
        <Minus />
      </WinButton>
      <WinButton frame={frame} label="Maximize" action="toggleMaximize">
        <Square className="size-3.5!" />
      </WinButton>
      <WinButton frame={frame} label="Close" action="close" danger>
        <X />
      </WinButton>
    </div>
  );
}
