// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Custom titlebar window buttons (Tauri decorations are off). Only the desktop app has them (HC-1 host.window): a browser
// and a phone draw their own frame, so nothing renders there (UI-WINCTRL-WEB-1: no dead buttons widening a phone page).
// DESK-5: a layout that draws the shell in a window of its own (the Desktop) hands its own frame as `controls`; the buttons then
// act on that window, not on the real one, and show in a browser too (they are not dead there). A phone has no window frame,
// so the layout's buttons are hidden below `sm`; its taskbar and app menu serve instead.
import { Copy, Minus, Square, X } from "lucide-react";
import type { ShellWindowControls, WindowFrame } from "@infantry/ui-plugin-api/contract";
import { cn } from "@infantry/ui";
import { host } from "@/host";

function WinButton({ label, onClick, danger, children }: { label: string; onClick: () => void; danger?: boolean; children: React.ReactNode }) {
  return (
    <button
      type="button"
      aria-label={label}
      title={label}
      onClick={onClick}
      className={cn(
        "grid h-full w-11 place-items-center text-muted-foreground transition-colors hover:bg-accent hover:text-foreground [&_svg]:size-4",
        danger && "hover:bg-danger hover:text-white",
      )}
    >
      {children}
    </button>
  );
}

export function WindowControls({ controls }: { controls?: ShellWindowControls } = {}) {
  const frame: WindowFrame | null = controls ?? host.window;
  if (!frame) return null;
  const maximized = controls?.maximized === true;
  return (
    <div className={cn("flex h-full items-stretch", controls && "max-sm:hidden")} data-window-controls={controls ? "app" : "window"}>
      <WinButton label="Minimize" onClick={() => frame.minimize()}>
        <Minus />
      </WinButton>
      <WinButton label={maximized ? "Restore" : "Maximize"} onClick={() => frame.toggleMaximize()}>
        {maximized ? <Copy className="size-3.5!" /> : <Square className="size-3.5!" />}
      </WinButton>
      <WinButton label="Close" onClick={() => frame.close()} danger>
        <X />
      </WinButton>
    </div>
  );
}
