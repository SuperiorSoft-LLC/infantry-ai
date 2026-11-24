// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// BOS-8: under BattalionOS (host.os) the title bar gets a "Desktop" button (back to the OS desktop; the app keeps running) and
// the agent counts go to the OS badge. Outside the OS both render nothing and send nothing.
import { useEffect } from "react";
import { Monitor } from "lucide-react";
import { IconButton } from "@infantry/ui";
import { host } from "@/host";
import { attentionStore } from "@/components/mail/use-mail";
import { useAgentActivity } from "@/plugins/agent-activity";

/** Pushes the working / needs-input counts to `os_badge` whenever they change. */
export function useOsBadge(): void {
  const { working, needsInput } = useAgentActivity();
  useEffect(() => {
    void host.os?.badge(working, needsInput);
  }, [working, needsInput]);
}

export function OsDesktopButton() {
  useOsBadge();
  const os = host.os;
  if (!os) return null;
  return (
    <IconButton
      label="Desktop"
      onClick={() => void os.showDesktop().catch((e) => attentionStore().getState().post({ id: "os-desktop", title: "Could not show the desktop", body: e instanceof Error ? e.message : String(e), level: "urgent" }))}
    >
      <Monitor />
    </IconButton>
  );
}
