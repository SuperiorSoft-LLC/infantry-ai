// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// PHONE-1: a phone whose PC stops answering says so, with Scan QR one tap away (the saved PC stays; the app keeps retrying).
import { useEffect, useState } from "react";
import { QrCode } from "lucide-react";
import { lostConnectionText } from "@infantry/client";
import { Button } from "@infantry/ui";
import { isMobile, remote, useDaemon } from "@/daemon/daemon";
import { askForConnectScreen } from "./connect-screen";

/** How long the link may be down before the banner shows (a short drop reconnects by itself). */
const GRACE_MS = 3000;

export function LostConnectionBanner() {
  if (!isMobile) return null;
  return <Banner />;
}

function Banner() {
  const c = useDaemon((s) => s.connection);
  const [late, setLate] = useState(false);
  useEffect(() => {
    if (c.state === "open") return setLate(false);
    const t = setTimeout(() => setLate(true), GRACE_MS);
    return () => clearTimeout(t);
  }, [c.state]);
  if (c.state === "open" || !late) return null;
  return (
    <div role="alert" data-testid="lost-connection" className="flex items-center gap-2 border-b border-danger/60 bg-danger/10 px-3 py-2 text-[13px]">
      <span className="min-w-0 flex-1">{lostConnectionText(remote?.name ?? null, c.reason)} Still trying.</span>
      <Button size="sm" className="h-9 shrink-0" onClick={askForConnectScreen}>
        <QrCode /> Scan QR
      </Button>
    </div>
  );
}
