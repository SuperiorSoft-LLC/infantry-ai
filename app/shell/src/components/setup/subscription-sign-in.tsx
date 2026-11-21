// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// "Log in with your subscription" (RUST PARITY of Kotlin ShellHarnessKeys `SubscriptionLogin`): ONE flow for every harness that
// declares an in-app login. Idle: Log in (the daemon runs the CLI's login and its page opens in a window of the app). Running: the page
// address, a field for the code or token the page shows, Cancel. Done: logged in. Failed: why, and Try again. The state is the
// daemon's (`loginState` on the row), so every window agrees. Nothing here names a harness; the token itself never comes here.
import { useEffect, useRef, useState } from "react";
import { useStore } from "zustand";
import { friendlyLoginError, loginRow, signInPhase, watchLoginWindow } from "@infantry/client";
import { Button, Input } from "@infantry/ui";
import { daemonStore } from "@/daemon/daemon";
import { secretsStore } from "@/daemon/secrets-store";
import { host } from "@/host";

/** How often the sign-in's state is read while it runs (a state read, not a limit). */
const POLL_MS = 1500;

export function SubscriptionSignIn({ id, label, onSignedIn }: { id: string; label: string; onSignedIn: () => void }) {
  const st = useStore(secretsStore());
  const row = loginRow(id, st.builtIns);
  const phase = signInPhase(row);
  const [code, setCode] = useState("");
  const [error, setError] = useState<string | null>(null);
  const opened = useRef<string>("");
  const win = useRef<{ close(): void } | null>(null);
  const reported = useRef(false);
  const submitted = useRef(new Set<string>());
  const [hint, setHint] = useState<string | null>(null);
  const finished = useRef(false);
  useEffect(() => st.ensureLoaded(), [st]);
  useEffect(() => {
    if (phase !== "running") return;
    const t = setInterval(() => daemonStore().getState().sendFrame({ type: "secret_status", data: "" }), POLL_MS);
    return () => clearInterval(t);
  }, [phase]);
  // The login page opens by itself in a window of the app, which reads the code off the page it ends on; the paste field below
  // covers every flow where no code is read. Closed with the sign-in (done, failed, cancelled) and with this screen.
  // One submit per code, and none once the login is over: the page can report its address more than once.
  finished.current = phase === "done" || phase === "failed" || row?.signedIn === "true";
  const submitCode = (text: string) => {
    if (finished.current || submitted.current.has(text)) return;
    submitted.current.add(text);
    st.login(row?.name ?? "", "input", text).catch((why) => setError(friendlyLoginError(why, finished.current)));
  };
  const openPage = (url: string) => {
    win.current?.close();
    setHint(null);
    void watchLoginWindow(
      host,
      url,
      row?.loginCodeUrls,
      submitCode,
      undefined,
      row?.loginAllowedHosts,
      () => setHint("Finish signing in in your browser. When the page shows a code, paste it here."),
    ).then((w) => (win.current = w));
  };
  useEffect(() => {
    if (row?.loginUrl && opened.current !== row.loginUrl) {
      opened.current = row.loginUrl;
      openPage(row.loginUrl);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [row?.loginUrl]);
  useEffect(() => {
    if (phase !== "running") {
      win.current?.close();
      win.current = null;
    }
  }, [phase]);
  useEffect(() => () => win.current?.close(), []);
  // Logged in: the caller asks the daemon again and moves on by itself (no Check button).
  useEffect(() => {
    if ((phase === "done" || row?.signedIn === "true") && !reported.current) {
      reported.current = true;
      onSignedIn();
    }
  }, [phase, row?.signedIn, onSignedIn]);

  if (!row) return null;
  const act = (action: "start" | "input" | "cancel", text = "") => {
    setError(null);
    if (action === "start") {
      reported.current = false;
      submitted.current.clear();
      setHint(null);
      setCode("");
    }
    st.login(row.name, action, text).catch((why) => setError(friendlyLoginError(why, finished.current)));
  };
  const invalid = row.loginMessage?.toLowerCase().startsWith("invalid");
  return (
    <div className="space-y-2" data-testid="subscription-sign-in">
      {phase === "running" ? (
        <>
          <p className="text-xs">Finish logging in in the login window. This screen updates by itself when you are done.</p>
          {row.loginUrl ? (
            <div className="flex flex-wrap items-center gap-2">
              <span className="text-xs text-muted-foreground">Window closed, or signing in with Google?</span>
              <Button size="sm" variant="outline" onClick={() => openPage(row.loginUrl!)}>
                Open the login page
              </Button>
              <Button
                size="sm"
                variant="ghost"
                onClick={() => {
                  win.current?.close();
                  win.current = null;
                  void host.openExternal(row.loginUrl!);
                }}
              >
                Use my browser instead
              </Button>
            </div>
          ) : null}
          {hint ? <p className="text-xs">{hint}</p> : null}
          <p className="text-xs text-muted-foreground">If the page shows a code, paste it here:</p>
          <div className="flex items-center gap-2">
            <Input
              type="password"
              autoComplete="off"
              spellCheck={false}
              aria-label="Code from the login page"
              placeholder="Code from the login page"
              value={code}
              onChange={(e) => setCode(e.target.value)}
              onKeyDown={(e) => e.key === "Enter" && code.trim() && (act("input", code), setCode(""))}
            />
            <Button size="sm" disabled={!code.trim()} onClick={() => (act("input", code), setCode(""))}>
              Submit
            </Button>
          </div>
          {row.loginMessage ? <p className={invalid ? "text-xs text-danger" : "text-xs text-muted-foreground"}>{row.loginMessage}</p> : null}
          <Button size="sm" variant="ghost" onClick={() => act("cancel")}>
            Cancel
          </Button>
        </>
      ) : phase === "done" || row.signedIn === "true" ? (
        <p className="text-xs text-ok">✓ Logged in with your {label} subscription.</p>
      ) : phase === "failed" ? (
        <>
          <p role="alert" className="text-xs text-danger">
            {row.loginMessage || "The login did not finish."}
          </p>
          <Button size="sm" variant="outline" onClick={() => act("start")}>
            Try again
          </Button>
        </>
      ) : (
        <Button size="sm" onClick={() => act("start")}>
          Log in
        </Button>
      )}
      {error ? (
        <p role="alert" className="text-xs text-danger">
          {error}
        </p>
      ) : null}
    </div>
  );
}
