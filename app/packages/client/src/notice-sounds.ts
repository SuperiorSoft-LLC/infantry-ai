// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// NOTIFY-CORE-4 (PARITY-AUDIT-1 #26): the sound a notice plays on this device and the OS-notification background gate.
// Settings (Infantry_Settings module.json, node `infantry.notify`): `notify.commsSound` (a `mail` frame arrives),
// `notify.levelSound.<level>` (a `notification_push` of that level), `notify.background` (OS notices only while the
// window is unfocused). Every default is "none" / off, so nothing new plays or hides until the owner sets it.
// Sounds are short generated tones (no audio files): [TONES].
import type { Envelope } from "./wire";

export const NOTIFY_NODE = "infantry.notify";
export const COMMS_SOUND_KEY = "notify.commsSound";
export const LEVEL_SOUND_PREFIX = "notify.levelSound.";
export const BACKGROUND_KEY = "notify.background";
export const NOTICE_LEVELS = ["info", "success", "warning", "error", "urgent"] as const;

/** One note: frequency (Hz), start offset and length (seconds). */
export interface ToneNote {
  freq: number;
  at: number;
  dur: number;
}

/** The built-in sounds, synthesised in the client (license-safe: no recordings). */
export const TONES: Record<string, ToneNote[]> = {
  chime: [
    { freq: 880, at: 0, dur: 0.18 },
    { freq: 1318.5, at: 0.12, dur: 0.3 },
  ],
  bell: [{ freq: 1046.5, at: 0, dur: 0.6 }],
  alarm: [
    { freq: 960, at: 0, dur: 0.12 },
    { freq: 720, at: 0.15, dur: 0.12 },
    { freq: 960, at: 0.3, dur: 0.12 },
    { freq: 720, at: 0.45, dur: 0.12 },
  ],
};

/** Reads one `infantry.notify` setting's value in effect (undefined = unset, use the default). */
export type NotifySettingRead = (key: string) => unknown;

function soundOf(v: unknown): string | null {
  return typeof v === "string" && v in TONES ? v : null;
}

/**
 * The built-in sound [env] should play here, or null (the default "none", an unknown name, or a frame that is not a
 * notice). A `mail` frame plays `notify.commsSound`; a `notification_push` plays `notify.levelSound.<level>`.
 */
export function noticeSoundOf(env: Envelope, read: NotifySettingRead): string | null {
  if (env.type === "mail") return soundOf(read(COMMS_SOUND_KEY));
  if (env.type === "notification_push") {
    try {
      const level = (JSON.parse(env.data) as { level?: string }).level ?? "info";
      return soundOf(read(LEVEL_SOUND_PREFIX + level));
    } catch {
      return null;
    }
  }
  return null;
}

/** Whether an OS notification may show: always, unless `notify.background` is on and the window has focus. */
export function osNoticeAllowed(read: NotifySettingRead, focused: boolean): boolean {
  return !(read(BACKGROUND_KEY) === true && focused);
}
