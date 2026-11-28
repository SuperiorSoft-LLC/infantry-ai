// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// POPOUT-1: "Open in its own window" for a session, only when BattalionOS can open native windows (see @infantry/client
// native-popout). Null hides the option (standalone, browser, phone, or already inside a pop-out window).
import { useEffect, useState } from "react";
import { nativePopoutAvailable, onPopoutWindowsChange, openSessionPopout, popoutViewOf, popoutWindows, type Windows } from "@infantry/client";
import { isMobile } from "@/daemon/daemon";
import { attentionStore } from "@/components/mail/use-mail";

/** The view this window shows alone (`?popout=session:<id>`), read once: a pop-out never changes what it shows. */
export const popoutView = typeof window === "undefined" ? null : popoutViewOf(window.location.search);

export function useNativePopout(): ((sessionId: string, title: string) => void) | null {
  const [ws, setWs] = useState<Windows | null>(null);
  useEffect(() => {
    if (isMobile || popoutView) return;
    let live = true;
    const pick = () => void popoutWindows().then((w) => live && setWs(w), () => undefined);
    pick();
    const off = onPopoutWindowsChange(pick);
    return () => ((live = false), off());
  }, []);
  if (!ws || !nativePopoutAvailable(ws, { mobile: isMobile, inPopout: !!popoutView })) return null;
  return (sessionId, title) =>
    void openSessionPopout(ws, { sessionId, title, pathname: window.location.pathname, search: window.location.search }).catch((e) =>
      attentionStore().getState().post({ id: `popout-${sessionId}`, title: "Could not open the window", body: e instanceof Error ? e.message : String(e), level: "urgent" }),
    );
}
