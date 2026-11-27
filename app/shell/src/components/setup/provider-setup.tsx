// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// PROVIDERS (659ca918): ONE provider setup, used by the setup wizard's sign-in step AND Settings > Providers, so the
// owner can check and change a key or sign-in at any time with the same component and the same daemon calls.
// It shows whether the key / sign-in is set, where it is stored (the vault row name), and whether an environment
// variable overrides it. Never a value.
import { useState } from "react";
import { signInChoices, type SecretRow } from "@infantry/client";
import { Button, Input } from "@infantry/ui";
import { SubscriptionSignIn } from "@/components/setup/subscription-sign-in";
import type { RunnerStores } from "@/daemon/runner-stores";

export function ChoiceCard({ title, subtitle, children }: { title: string; subtitle?: string; children: React.ReactNode }) {
  return (
    <div className="space-y-2 rounded-lg border bg-card p-3">
      <p className="text-[13px] font-semibold">{title}</p>
      {subtitle ? <p className="text-xs text-muted-foreground">{subtitle}</p> : null}
      {children}
    </div>
  );
}

/** The harness ids that have a credential row (a sign-in token or an API key) in [rows]: the daemon declares them. */
export function providerIds(rows: SecretRow[]): string[] {
  const ids = new Set<string>();
  for (const r of rows) {
    const m = /^harness\.(.+)\.(token|key)$/.exec(r.name);
    if (m) ids.add(m[1]);
  }
  return [...ids].sort();
}

/** One line per credential row: set or not, where it lives, and an env override. Never the value. */
function CredentialState({ row, kind }: { row: SecretRow; kind: string }) {
  const set = row.set || !!row.usesSecret;
  return (
    <p className="text-xs text-muted-foreground" data-testid={`provider-state-${row.name}`}>
      {kind}: <span className={set ? "text-foreground" : "text-warn"}>{set ? "set" : "not set"}</span>
      {" · stored in the vault as "}
      <span className="font-mono">{row.usesSecret ? `secret ${row.usesSecret}` : row.name}</span>
      {row.overriddenByEnv ? " · an environment variable of the daemon overrides it" : ""}
    </p>
  );
}

/** Sign in or set the API key of harness [id] on the runner of [st]. */
export function ProviderSetup({ id, label, rows, recheck, st, named = false }: { id: string; label: string; rows: SecretRow[]; recheck: () => void; st: RunnerStores; named?: boolean }) {
  const [key, setKey] = useState("");
  const [error, setError] = useState<string | null>(null);
  const { login, key: keyRowOf, keyNeeded } = signInChoices(id, rows);
  const showKey = !!keyRowOf && keyRowOf.authModes?.includes("api-key") !== false;
  return (
    <div className="space-y-2.5" data-testid={`provider-${id}`}>
      <p className="text-[13px] font-semibold">{label}</p>
      <p className="text-xs text-muted-foreground">{login && showKey ? `Choose one way to connect ${label}.` : `Connect ${label} so your agent can answer.`}</p>
      {login && (
        <ChoiceCard title={`Log in with your ${label} subscription`} subtitle={`A login window opens so you can log in to your ${label} account.`}>
          <CredentialState row={login} kind="Sign-in" />
          <SubscriptionSignIn id={id} label={label} onSignedIn={recheck} secrets={st.secrets} runner={st.runner} named={named} />
        </ChoiceCard>
      )}
      {showKey && keyRowOf && (
        <ChoiceCard title={login ? `Use an API key` : `${label} API key`} subtitle={keyRowOf.help}>
          <CredentialState row={keyRowOf} kind="API key" />
          {keyNeeded ? (
            <div className="flex gap-2">
              <Input aria-label="API key" type="password" autoComplete="off" value={key} onChange={(e) => setKey(e.target.value)} placeholder={keyRowOf.set ? "New API key" : "API key"} />
              <Button
                size="sm"
                disabled={!key.trim()}
                onClick={() => st.secrets.getState().setField(keyRowOf.name, "value", key.trim()).then(() => (setKey(""), recheck()), (why: unknown) => setError(String(why)))}
              >
                {keyRowOf.set ? "Replace key" : "Save key"}
              </Button>
            </div>
          ) : (
            <p className="text-xs text-muted-foreground">Not needed: you are logged in with your subscription.</p>
          )}
        </ChoiceCard>
      )}
      {error ? (
        <p role="alert" className="text-xs text-danger">
          {error}
        </p>
      ) : null}
    </div>
  );
}
