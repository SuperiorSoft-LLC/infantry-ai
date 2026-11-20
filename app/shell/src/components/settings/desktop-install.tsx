// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// RUST-UPD-1: the installed desktop app's own settings (src-tauri update.rs, pairing.rs): updates (feed, channel,
// check, install), `infantry` on PATH (paths.addCli, opt-in), and pairing a phone (QR / infantry://connect link, the
// token only behind an explicit reveal). Each row hides itself where its Tauri command does not exist (a browser, a phone).
import { useEffect, useMemo, useState } from "react";
import { Eye, EyeOff, Copy, RefreshCw, Download } from "lucide-react";
import qrcode from "qrcode-generator";
import { desktopPairingLink, qrPath, updateNotice, type UpdateInfo } from "@infantry/client";
import { Button, Input, Label, ProgressLine, Select, SelectContent, SelectItem, SelectTrigger, SelectValue, Switch } from "@infantry/ui";
import { isMobile } from "@/daemon/daemon";
import { host } from "@/host";
import { attentionStore } from "@/components/mail/use-mail";

/** One of the app's own commands (HC-1 host.call; src-tauri update.rs, pairing.rs, android_update.rs). */
const call = <T,>(cmd: string, args?: Record<string, unknown>): Promise<T> => host.call<T>(cmd, args);

const desktop = () => host.kind === "desktop" && !isMobile;
// RUST-UPD-2: the update commands exist on the desktop (Tauri updater plugin) and on Android (android_update.rs); iOS has none.
const updatable = () => host.kind !== "browser";
const message = (e: unknown) => (e instanceof Error ? e.message : String(e));

function Row({ id, title, hint, children }: { id: string; title: string; hint: React.ReactNode; children?: React.ReactNode }) {
  return (
    <div className="flex items-center justify-between gap-4 rounded-lg border bg-card p-3">
      <div className="min-w-0">
        <Label htmlFor={id}>{title}</Label>
        <div className="text-xs text-muted-foreground">{hint}</div>
      </div>
      {children}
    </div>
  );
}

/** At start (desktop, `updater.autoCheck` on): one check; a newer build posts an "update available" notice. Never installs. */
/** RUST-BUG 2617b342: the app restarted a daemon that died (or could not); the window posts it as an owner notice. */
export function listenDaemonNotices(): void {
  if (host.kind === "browser") return;
  void host
    .listen<{ id: string; title: string; body: string; level: "urgent" }>("daemon-notice", (n) => attentionStore().getState().post(n))
    .catch((e) => console.warn(`[infantry] daemon notices: ${message(e)}`));
}

/** The newest check (start, timer or button), for the "up to date (0.1.N, checked HH:MM)" line. */
let lastCheck: Date | null = null;
const hhmm = (d: Date) => d.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });

export function startUpdateCheck(): void {
  if (!updatable()) return;
  const run = () =>
    call<UpdateInfo>("update_check")
      .then((u) => {
        lastCheck = new Date();
        const n = updateNotice(u);
        if (n) attentionStore().getState().post(n);
      })
      .catch((e) => console.warn(`[infantry] update check: ${message(e)}`));
  void call<{ autoCheck: boolean; checkMinutes: number }>("update_settings_get")
    .then((s) => {
      if (!s.autoCheck) return;
      void run();
      // `updater.checkMinutes` (default 60; 0 = only at start): the open app keeps looking.
      if (s.checkMinutes > 0) setInterval(() => void run(), s.checkMinutes * 60_000);
    })
    .catch((e) => console.warn(`[infantry] update check: ${message(e)}`));
}

interface UpdaterView {
  feedUrl: string;
  defaultFeedUrl: string;
  channel: string;
  channels: string[];
  autoCheck: boolean;
  checkMinutes: number;
  autoInstall: boolean;
  signed: boolean;
  current: string;
}

/** Settings > About: version, channel, feed, check and install. */
export function UpdaterRows() {
  const [v, setV] = useState<UpdaterView | null>(null);
  const [feed, setFeed] = useState("");
  const [found, setFound] = useState<UpdateInfo | null>(null);
  const [busy, setBusy] = useState<"check" | "install" | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [progress, setProgress] = useState<{ state: string; done: number; total: number; message: string } | null>(null);
  const load = () => call<UpdaterView>("update_settings_get").then((x) => (setV(x), setFeed(x.feedUrl)), () => setV(null));
  useEffect(() => {
    if (updatable()) void load();
  }, []);
  // Android: the download runs in the background, so the bar polls its progress. The desktop installs in one call.
  useEffect(() => {
    if (busy !== "install" || !isMobile) return;
    const t = setInterval(() => {
      void call<{ state: string; done: number; total: number; message: string }>("update_progress").then((p) => {
        setProgress(p);
        if (p.state === "error") (setError(p.message), setBusy(null));
        if (p.state === "done") setBusy(null);
      }, () => undefined);
    }, 500);
    return () => clearInterval(t);
  }, [busy]);
  if (!v) return null;
  const save = (patch: Record<string, unknown>) =>
    call("update_settings_set", patch).then(load, (e) => setError(message(e)));
  const check = async () => {
    setBusy("check");
    setError(null);
    try {
      const u = await call<UpdateInfo>("update_check");
      lastCheck = new Date();
      setFound(u);
    } catch (e) {
      setError(message(e));
    } finally {
      setBusy(null);
    }
  };
  const install = async () => {
    setBusy("install");
    setError(null);
    setProgress(null);
    try {
      await call("update_install");
    } catch (e) {
      setError(message(e));
      setBusy(null);
    }
  };
  return (
    <>
      <Row id="upd-channel" title="Update channel" hint={`You run ${v.current}. Stable: tested releases. Nightly: the newest work, tested less.`}>
        <Select value={v.channel} onValueChange={(c) => void save({ channel: c })}>
          <SelectTrigger id="upd-channel" className="w-32">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {v.channels.map((c) => (
              <SelectItem key={c} value={c}>
                {c === "stable" ? "Stable" : "Nightly"}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </Row>
      <Row id="upd-auto" title="Check for updates automatically" hint={`At start and ${v.checkMinutes > 0 ? `every ${v.checkMinutes} minutes` : "on the button only"}. Installing is always your call.`}>
        <Switch id="upd-auto" checked={v.autoCheck} onCheckedChange={(on) => void save({ autoCheck: on })} />
      </Row>
      <div className="space-y-1.5 rounded-lg border bg-card p-3">
        <Label htmlFor="upd-feed">Update feed</Label>
        <form
          className="flex gap-2"
          onSubmit={(e) => {
            e.preventDefault();
            void save({ feedUrl: feed });
          }}
        >
          <Input id="upd-feed" className="font-mono text-xs" spellCheck={false} value={feed} onChange={(e) => setFeed(e.target.value)} />
          <Button type="submit" variant="outline" size="sm" disabled={feed === v.feedUrl}>
            Save
          </Button>
          {v.feedUrl !== v.defaultFeedUrl && (
            <Button type="button" variant="ghost" size="sm" onClick={() => void save({ feedUrl: "" })}>
              Default
            </Button>
          )}
        </form>
        <p className="text-xs text-muted-foreground">
          The default reads GitHub's newest release live (nightly: the newest prerelease). A custom feed may use <code>{"{channel}"}</code>. {v.signed ? "Updates are verified with this build's signing key." : "This build has no signing key yet, so updates can be found but not installed."}
        </p>
      </div>
      <Row
        id="upd-check"
        title={found?.available ? `${found.version} available` : found ? `Up to date (${found.current}, checked ${hhmm(lastCheck ?? new Date())})` : "Updates"}
        hint={
          error ? (
            <span className="text-danger">{error}</span>
          ) : busy === "install" && isMobile ? (
            <div className="w-56 space-y-1">
              <ProgressLine value={progress && progress.state === "downloading" && progress.total > 0 ? Math.round((progress.done / progress.total) * 100) : null} />
              <div>{progress?.state === "verifying" ? "Checking the download…" : progress?.state === "installing" ? "Confirm the install on the screen that opens." : "Downloading…"}</div>
            </div>
          ) : found?.available ? (
            (found.notes ?? (isMobile ? "The new version downloads here, then Android asks you to confirm the install." : "The daemon stops, the installer replaces the files, and the app starts again."))
          ) : (
            "Check the feed for a newer build."
          )
        }
      >
        <div className="flex gap-2">
          <Button variant="outline" size="sm" disabled={busy !== null} onClick={() => void check()}>
            <RefreshCw /> {busy === "check" ? "Checking…" : "Check"}
          </Button>
          {found?.available && (
            <Button size="sm" disabled={busy !== null || !found.canInstall} onClick={() => void install()}>
              <Download /> {busy === "install" ? (isMobile ? "Downloading…" : "Installing…") : isMobile ? "Download and install" : "Install and restart"}
            </Button>
          )}
        </div>
      </Row>
    </>
  );
}

/** Settings > Daemon: `infantry` on the user PATH (paths.addCli, opt-in). Only an installed app shows it. */
export function CliPathRow() {
  const [v, setV] = useState<{ available: boolean; on: boolean; dir: string | null } | null>(null);
  const [note, setNote] = useState<string | null>(null);
  useEffect(() => {
    if (desktop()) void call<typeof v>("cli_path_get").then(setV, () => setV(null));
  }, []);
  if (!v?.available) return null;
  const change = (on: boolean) =>
    call<string>("cli_path_set", { on }).then(
      (out) => (setV({ ...v, on }), setNote(out.split("\n").pop() ?? null)),
      (e) => setNote(message(e)),
    );
  return (
    <Row id="set-cli-path" title="Add `infantry` to PATH" hint={note ?? "Your user PATH only. Open a new terminal after a change."}>
      <Switch id="set-cli-path" checked={v.on} onCheckedChange={(on) => void change(on)} />
    </Row>
  );
}

interface PairingView {
  available: boolean;
  enabled: boolean;
  port: number | null;
  addresses: { address: string; label: string }[];
  hostName: string | null;
  token: string | null;
}

function Qr({ text }: { text: string }) {
  const { size, d } = useMemo(() => {
    const qr = qrcode(0, "M");
    qr.addData(text);
    qr.make();
    const n = qr.getModuleCount();
    return { size: n, d: qrPath(n, (r, c) => qr.isDark(r, c)) };
  }, [text]);
  return (
    <svg role="img" aria-label="Pairing QR code" viewBox={`-2 -2 ${size + 4} ${size + 4}`} className="size-48 rounded-md bg-white" shapeRendering="crispEdges">
      <path d={d} fill="#000" />
    </svg>
  );
}

/** Settings > Daemon: let a phone connect (RUST-MOBILE-1), and the QR / link it scans. */
export function PairingRows() {
  const [v, setV] = useState<PairingView | null>(null);
  const [address, setAddress] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const load = (reveal: boolean) =>
    call<PairingView>("pairing_get", { reveal }).then(
      (x) => (setV(x), setAddress((a) => (a && x.addresses.some((o) => o.address === a) ? a : (x.addresses[0]?.address ?? null)))),
      () => setV(null),
    );
  useEffect(() => {
    if (desktop()) void load(false);
  }, []);
  if (!v?.available) return null;
  const toggle = async (on: boolean) => {
    setBusy(true);
    setError(null);
    try {
      await call("pairing_set", { enabled: on });
      location.reload(); // the sidecar restarted with a new bind (and token): reconnect through `endpoint`.
    } catch (e) {
      setError(message(e));
      setBusy(false);
    }
  };
  const link = v.enabled && address && v.port ? desktopPairingLink(address, v.port, v.token, v.hostName) : null;
  return (
    <>
      <Row
        id="set-phone"
        title="Allow phone connections"
        hint={error ? <span className="text-danger">{error}</span> : "Restarts the daemon on every network interface with a token. Off: this computer only."}
      >
        <Switch id="set-phone" checked={v.enabled} disabled={busy} onCheckedChange={(on) => void toggle(on)} />
      </Row>
      {v.enabled && (
        <div className="space-y-3 rounded-lg border bg-card p-3">
          <div className="flex flex-wrap items-end gap-3">
            <div className="min-w-48 flex-1 space-y-1.5">
              <Label htmlFor="pair-address">Address the phone uses</Label>
              {v.addresses.length ? (
                <Select value={address ?? undefined} onValueChange={setAddress}>
                  <SelectTrigger id="pair-address" className="w-full">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {v.addresses.map((a) => (
                      <SelectItem key={a.address} value={a.address}>
                        {a.address} · {a.label}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              ) : (
                <p className="text-xs text-danger">This computer has no network address a phone can reach.</p>
              )}
            </div>
            {v.token ? (
              <Button variant="outline" size="sm" onClick={() => void load(false)}>
                <EyeOff /> Hide
              </Button>
            ) : (
              <Button variant="outline" size="sm" disabled={!address} onClick={() => void load(true)}>
                <Eye /> Show pairing code
              </Button>
            )}
          </div>
          {link && v.token ? (
            <div className="flex flex-wrap items-start gap-4">
              <Qr text={link} />
              <div className="min-w-0 flex-1 space-y-2">
                <p className="text-xs text-muted-foreground">Scan it with Infantry AI on the phone, or send it the link. It carries the token: share it only with your own devices.</p>
                <p className="font-mono text-[11px] break-all">{link}</p>
                <Button variant="outline" size="sm" onClick={() => void navigator.clipboard?.writeText(link)}>
                  <Copy /> Copy link
                </Button>
              </div>
            </div>
          ) : (
            <p className="text-xs text-muted-foreground">The pairing code includes the daemon token, so it stays hidden until you ask for it.</p>
          )}
        </div>
      )}
    </>
  );
}
