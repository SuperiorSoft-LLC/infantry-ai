// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The login window's address watcher (pure). A harness declares the addresses its login ends on (`loginCodeUrls` on the
// secret row: scheme, host and path of the page that carries `code=`); nothing here names a harness or a vendor.
import type { LoginWindow, LoginWindowHandlers } from "./host";

/**
 * The code a login page address carries, or null. [url] counts when it starts with one of [prefixes] on a path boundary
 * (so `/callback` does not match `/callback-evil`). A `state` next to the code is appended as `code#state`, the form the
 * page itself shows the owner to paste.
 */
export function loginCodeFromUrl(url: string, prefixes: readonly string[] | undefined): string | null {
  if (!prefixes?.length) return null;
  let u: URL;
  try {
    u = new URL(url);
  } catch {
    return null;
  }
  if (u.protocol !== "https:") return null;
  const here = `${u.origin}${u.pathname}`.replace(/\/+$/, "");
  if (!prefixes.some((p) => here === p.replace(/\/+$/, ""))) return null;
  const code = u.searchParams.get("code")?.trim();
  if (!code) return null;
  const state = u.searchParams.get("state")?.trim();
  return state ? `${code}#${state}` : code;
}

/** True when [url] is a page of one of [hosts] (exact host, so a look-alike suffix does not count). */
export function isExternalOnlyHost(url: string, hosts: readonly string[] | undefined): boolean {
  if (!hosts?.length) return false;
  try {
    const h = new URL(url).hostname.toLowerCase();
    return hosts.some((x) => x.toLowerCase() === h);
  } catch {
    return false;
  }
}

/** The addresses the host window reports: the declared code pages plus a bare origin per external-only host. */
export function loginWatchList(prefixes: readonly string[] | undefined, hosts: readonly string[] | undefined): string[] {
  return [...(prefixes ?? []), ...(hosts ?? []).map((h) => `https://${h}`)];
}

/**
 * What the sign-in screen shows for a failed login input, or null when nothing should show: a refusal because no sign-in runs
 * any more means the login already ended, so it is dropped when [finished]; the daemon's frame prefix never reaches the owner.
 */
export function friendlyLoginError(why: unknown, finished: boolean): string | null {
  const raw = (why instanceof Error ? why.message : String(why)).replace(/^\s*secret_login refused:\s*/i, "").trim();
  if (/no sign-in is running|no longer takes input/i.test(raw)) return finished ? null : "The sign-in is not running. Press Log in to start it again.";
  return raw || "The sign-in could not take that code. Try again.";
}

/** What [watchLoginWindow] needs of the host (a [Host] has it). */
export interface LoginWindowOpener {
  openLoginWindow(url: string, watch: readonly string[], h: LoginWindowHandlers): Promise<LoginWindow | null>;
  openExternal(url: string): Promise<void>;
}

/**
 * Opens the login page in the app's own window and watches it: the first address that carries a code is handed to [submit]
 * (the running login's input) and the window closes, so the owner copies nothing. When no window opens (a blocked popup, an
 * error) the page opens in the system browser instead; the caller's paste field covers every case where no code is read.
 * Returns a handle that closes the window ([close] is safe to call twice).
 */
export async function watchLoginWindow(
  host: LoginWindowOpener,
  url: string,
  prefixes: readonly string[] | undefined,
  submit: (code: string) => void,
  onClosed: () => void = () => undefined,
  externalHosts?: readonly string[],
  onExternal: () => void = () => undefined,
): Promise<{ close(): void }> {
  let win: LoginWindow | null = null;
  let sent = false;
  const handlers: LoginWindowHandlers = {
    onUrl(u) {
      if (sent) return;
      if (isExternalOnlyHost(u, externalHosts)) {
        // The page refuses an embedded window: the same login page opens in the system browser, the paste field takes the code.
        sent = true;
        win?.close();
        void host.openExternal(url).catch(() => undefined);
        onExternal();
        return;
      }
      const code = loginCodeFromUrl(u, prefixes);
      if (!code) return;
      sent = true;
      submit(code);
      win?.close();
    },
    onClosed,
  };
  try {
    win = await host.openLoginWindow(url, loginWatchList(prefixes, externalHosts), handlers);
  } catch {
    win = null;
  }
  if (!win) await host.openExternal(url).catch(() => undefined);
  return { close: () => win?.close() };
}
