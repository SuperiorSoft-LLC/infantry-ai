// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// PARITY-AUDIT-1 #5: the main window raises an OS notification for each bus notice the daemon routed to `os`
// (@infantry/client createOsNotifier: once per notice, repeats for a ticket or session within 60 s collapsed). The
// in-app toast still comes from ToastHost; a host with no notifier (browser, BattalionOS) shows toasts only. A click
// (the host brings the window up; showing never takes focus) opens the chat or the board the notice names.
import { useEffect } from "react";
import { createOsNotifier, osNoticeAllowed } from "@infantry/client";
import { daemonStore } from "@/daemon/daemon";
import { host } from "@/host";
import { useUi } from "@/state/ui-state";
import { readNotifySetting } from "./notice-sound-host";

export function OsNoticeHost() {
  const ui = useUi();
  const set = ui.set;
  useEffect(() => {
    const notify = host.notify;
    if (!notify) return;
    const os = createOsNotifier((n) => {
      // NOTIFY-CORE-4: notify.background on = no OS notification while this window has focus (the toast still shows).
      if (osNoticeAllowed(readNotifySetting, document.hasFocus())) void notify.show(n);
    });
    const offFrames = daemonStore().getState().onFrame((env) => void os.frame(env));
    const offClicks = notify.onClick((key) => {
      const n = os.notices.get(key);
      if (n?.sessionId) set("selectedAgentId", n.sessionId);
      else if (n?.ticketId) set("dialog", "board");
    });
    return () => {
      offFrames();
      offClicks();
    };
  }, [set]);
  return null;
}
