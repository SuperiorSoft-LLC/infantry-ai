// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Pairing a phone with a remote daemon (RUST-MOBILE-1), like the Kotlin infantryAndroid ConnectScreen: scan the QR code a
// desktop shows, paste its `infantry://connect?...` link, or enter the address and token. The token goes to the
// platform keystore through `remote_save` (mobile.rs), never to page storage. Pairing reloads the window into the shell.
import { useEffect, useState } from "react";
import { Link2, QrCode, Unplug } from "lucide-react";
import { parsePairingLink, socketUrl, type Pairing } from "@infantry/client";
import { Button, Input, Label, PanelCard } from "@infantry/ui";
import { isMobile, remote } from "@/daemon/daemon";

async function save(p: Pairing): Promise<void> {
  const { invoke } = await import("@tauri-apps/api/core");
  await invoke<string>("remote_save", { url: p.url, token: p.token, name: p.name });
  location.reload();
}

/** Scans one QR code with the camera (the barcode-scanner plugin); null when cancelled or refused. */
async function scanQr(): Promise<string | null> {
  const bs = await import("@tauri-apps/plugin-barcode-scanner");
  let perm = await bs.checkPermissions();
  if (perm !== "granted") perm = await bs.requestPermissions();
  if (perm !== "granted") throw new Error("the camera permission was refused");
  const r = await bs.scan({ formats: [bs.Format.QRCode], windowed: false });
  return r?.content ?? null;
}

export function ConnectScreen() {
  const [url, setUrl] = useState("");
  const [token, setToken] = useState("");
  const [name, setName] = useState<string | null>(null);
  const [link, setLink] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    let light = false;
    try {
      light = localStorage.getItem("infantry.theme") === "light";
    } catch {
      /* no storage: the dark default */
    }
    document.documentElement.classList.toggle("dark", !light);
  }, []);

  const fill = (text: string) => {
    const r = parsePairingLink(text);
    if (!r.ok) return setError(r.reason);
    setError(null);
    setUrl(r.pairing.url);
    setToken(r.pairing.token ?? "");
    setName(r.pairing.name);
  };

  const connect = async (p: Pairing) => {
    setBusy(true);
    setError(null);
    try {
      await save(p);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      setBusy(false);
    }
  };

  const scan = async () => {
    setError(null);
    try {
      const text = await scanQr();
      if (!text) return;
      const r = parsePairingLink(text);
      if (!r.ok) return setError(`That QR code is ${r.reason}.`);
      await connect(r.pairing);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  };

  return (
    <main className="flex h-full items-start justify-center overflow-y-auto bg-background p-4 text-foreground sm:items-center">
      <div className="w-full max-w-md space-y-4">
        <div>
          <h1 className="text-lg font-semibold">Connect to your daemon</h1>
          <p className="text-xs text-muted-foreground">This phone runs no agents. It connects to the Infantry daemon on your computer or server.</p>
        </div>
        {isMobile && (
          <Button className="h-12 w-full" disabled={busy} onClick={() => void scan()}>
            <QrCode /> Scan the pairing QR code
          </Button>
        )}
        <PanelCard title="Paste a pairing link">
          <form
            className="flex gap-2"
            onSubmit={(e) => {
              e.preventDefault();
              fill(link);
            }}
          >
            <Input aria-label="Pairing link" placeholder="infantry://connect?url=…" autoCapitalize="off" autoCorrect="off" spellCheck={false} value={link} onChange={(e) => (setLink(e.target.value), e.target.value.startsWith("infantry://") && fill(e.target.value))} />
            <Button type="submit" variant="outline" aria-label="Read link">
              <Link2 />
            </Button>
          </form>
        </PanelCard>
        <PanelCard title="Or enter it">
          <form
            className="space-y-3"
            onSubmit={(e) => {
              e.preventDefault();
              if (url.trim()) void connect({ url: socketUrl(url), token: token.trim() || null, name });
            }}
          >
            <div className="space-y-1.5">
              <Label htmlFor="pair-url">Daemon address</Label>
              <Input id="pair-url" inputMode="url" autoCapitalize="off" autoCorrect="off" spellCheck={false} placeholder="ws://100.64.0.1:18990/ws" value={url} onChange={(e) => setUrl(e.target.value)} />
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="pair-token">Token</Label>
              <Input id="pair-token" type="password" autoComplete="off" placeholder="From `infantry pair` on the daemon's machine" value={token} onChange={(e) => setToken(e.target.value)} />
            </div>
            {name && <p className="text-xs text-muted-foreground">Daemon: {name}</p>}
            {error && (
              <p role="alert" className="text-xs text-danger">
                {error}
              </p>
            )}
            <Button type="submit" className="h-11 w-full" disabled={busy || !url.trim()}>
              {busy ? "Connecting…" : "Connect"}
            </Button>
          </form>
        </PanelCard>
      </div>
    </main>
  );
}

/** Settings > Daemon on a phone: the paired daemon, and Forget (back to the connect screen). */
export function RemoteRow() {
  const [error, setError] = useState<string | null>(null);
  if (!isMobile || !remote) return null;
  const forget = async () => {
    try {
      const { invoke } = await import("@tauri-apps/api/core");
      await invoke("remote_forget");
      location.reload();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  };
  return (
    <div className="flex items-center justify-between gap-4 rounded-lg border bg-card p-3">
      <div className="min-w-0">
        <div className="text-sm font-medium">{remote.name ?? "Paired daemon"}</div>
        <p className="truncate font-mono text-xs text-muted-foreground">{remote.url}</p>
        {error && <p className="text-xs text-danger">{error}</p>}
      </div>
      <Button variant="outline" size="sm" onClick={() => void forget()}>
        <Unplug /> Forget
      </Button>
    </div>
  );
}
