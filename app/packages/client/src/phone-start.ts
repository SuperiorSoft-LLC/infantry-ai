// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// PHONE-1: what the phone app opens on. No daemon runs on a phone: with no working PC it opens the connect screen (Scan QR
// first), never the shell and never a "daemon did not start" message. The saved pairing is kept until Forget or a new pairing.

/** What the Rust side (`remote_check`, mobile.rs) found out about the saved PC. */
export type RemoteCheck = "ok" | "unreachable" | "refused";

export type PhoneStartInput = {
  /** The runner mode the app reported (`remote` on a phone); undefined when the answer carried none. */
  mode?: string | null;
  /** The saved address ("" or missing = never paired). */
  url?: string | null;
  /** The keystore could not be read (the endpoint's `error`). */
  tokenError?: string | null;
  /** The answer of the reachability check; null when it could not be run. */
  check: RemoteCheck | null;
};

export type ConnectWhy = "no-pairing" | "unreachable" | "refused" | "unreadable-token" | "asked";

export type PhoneStart = { open: "app" } | { open: "connect"; why: ConnectWhy };

/** The start decision of a phone. A missing mode on a phone is still a phone (the caller says so with `phone`). */
export function decidePhoneStart(i: PhoneStartInput): PhoneStart {
  if (!i.url || !i.url.trim()) return { open: "connect", why: "no-pairing" };
  if (i.tokenError) return { open: "connect", why: "unreadable-token" };
  if (i.check === "refused") return { open: "connect", why: "refused" };
  if (i.check === "ok") return { open: "app" };
  return { open: "connect", why: "unreachable" };
}

/** The reason in plain words, for a phone user. `pc` is the PC's name (or its address). */
export function connectReasonText(why: ConnectWhy, pc: string | null): string {
  const who = pc ? pc : "your PC";
  switch (why) {
    case "no-pairing":
      return "This phone is not connected to a PC yet. Scan the QR code your PC shows.";
    case "unreachable":
      return `${who} did not answer. Check that it is on, that Infantry AI is open on it and that the phone can reach it, then try again or scan its QR code.`;
    case "refused":
      return `${who} no longer accepts this phone's saved login. Scan its QR code again to pair the phone.`;
    case "unreadable-token":
      return `The saved login for ${who} could not be read on this phone. Scan its QR code again to pair the phone.`;
    case "asked":
      return `Scan the QR code of a PC to connect to it. ${who} stays saved until you forget it.`;
  }
}

/** The banner text of a link that dropped while the app was open. */
export function lostConnectionText(pc: string | null, reason?: string): string {
  return `${pc ?? "Your PC"} is not answering${reason ? ` (${reason})` : ""}.`;
}
