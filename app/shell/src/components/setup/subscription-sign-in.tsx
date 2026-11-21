// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// "Sign in with your subscription": the browser sign-in a harness declares as a built-in secret row (`harness.<id>.token`),
// used by the guided setup and by Settings > Secrets. The daemon runs the CLI's own token command (the browser opens), the
// owner pastes the token it shows, and the daemon keeps it in the vault (never here, never in a log). Nothing names a harness.
import { useEffect, useRef, useState } from "react";
import { useStore } from "zustand";
import { signInPhase, signInRow } from "@infantry/client";
import { Button, Input } from "@infantry/ui";
import { daemonStore } from "@/daemon/daemon";
import { secretsStore } from "@/daemon/secrets-store";

/** How often the sign-in's state is read while it runs (a state read, not a limit). */
const POLL_MS = 1500;

export function SubscriptionSignIn({ id, label, onSignedIn }: { id: string; label: string; onSignedIn: () => void }) {
  const st = useStore(secretsStore());
  const row = signInRow(id, st.builtIns);
  const phase = signInPhase(row);
  const [pasted, setPasted] = useState("");
  const [error, setError] = useState<string | null>(null);
  const opened = useRef<string>("");
  const reported = useRef(false);
  useEffect(() => st.ensureLoaded(), [st]);
  // While the sign-in runs the daemon's state is the progress.
  useEffect(() => {
    if (phase !== "running") return;
    const t = setInterval(() => daemonStore().getState().sendFrame({ type: "secret_status", data: "" }), POLL_MS);
    return () => clearInterval(t);
  }, [phase]);
  // The browser opens by itself when the sign-in page address arrives.
  useEffect(() => {
    if (row?.loginUrl && opened.current !== row.loginUrl) {
      opened.current = row.loginUrl;
      window.open(row.loginUrl, "_blank");
    }
  }, [row?.loginUrl]);
  // Signed in: the caller asks the daemon again (the provider is available now) and moves on.
  useEffect(() => {
    if ((phase === "done" || row?.signedIn === "true") && !reported.current) {
      reported.current = true;
      onSignedIn();
    }
  }, [phase, row?.signedIn, onSignedIn]);

  if (!row) {
    return (
      <p className="text-xs text-muted-foreground">
        Open a terminal and start <span className="font-mono">{id}</span>. It asks you to sign in (a browser window opens). Come back here and press Check.
      </p>
    );
  }
  const run = (action: "start" | "cancel") => {
    setError(null);
    reported.current = false;
    st.login(row.name, action).catch((why) => setError(String(why)));
  };
  const save = () => {
    const value = pasted;
    setPasted("");
    setError(null);
    reported.current = false;
    st.login(row.name, "input", value).catch((why) => setError(String(why)));
  };
  return (
    <div className="space-y-2" data-testid="subscription-sign-in">
      <div className="flex flex-wrap items-center gap-2">
        <Button size="sm" disabled={phase === "running"} onClick={() => run("start")}>
          {phase === "running" ? "Waiting for the browser..." : `Sign in with your ${label} subscription`}
        </Button>
        {row.loginUrl && phase === "running" && (
          <Button size="sm" variant="outline" onClick={() => window.open(row.loginUrl, "_blank")}>
            Open the sign-in page again
          </Button>
        )}
        {phase === "running" && (
          <Button size="sm" variant="ghost" onClick={() => run("cancel")}>
            Cancel
          </Button>
        )}
      </div>
      <p className="text-xs text-muted-foreground">
        {phase === "running"
          ? "Log in in the browser window, then copy the token it shows and paste it below."
          : "A browser window opens. Log in, copy the token it shows, and paste it here."}
      </p>
      <div className="flex items-center gap-2">
        <Input
          type="password"
          autoComplete="off"
          spellCheck={false}
          aria-label={`${label} subscription token`}
          placeholder="Paste the token"
          value={pasted}
          onChange={(e) => setPasted(e.target.value)}
          onKeyDown={(e) => e.key === "Enter" && pasted.trim() && save()}
        />
        <Button size="sm" disabled={!pasted.trim()} onClick={save}>
          Save token
        </Button>
      </div>
      {phase === "done" || row.signedIn === "true" ? <p className="text-xs">Signed in.</p> : null}
      {(error || (phase === "failed" && row.loginMessage)) && (
        <p role="alert" className="text-xs text-danger">
          {error ?? row.loginMessage}
        </p>
      )}
    </div>
  );
}
