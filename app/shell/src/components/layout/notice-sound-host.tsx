// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// NOTIFY-CORE-4: plays the sound the owner chose for a notice kind (@infantry/client noticeSoundOf: incoming comms, each
// notice level) as a generated tone. Unset = "none" = silent, so nothing plays until a sound is picked in Settings.
import { useEffect } from "react";
import { NOTIFY_NODE, TONES, noticeSoundOf, settingKey, type NotifySettingRead } from "@infantry/client";
import { daemonStore } from "@/daemon/daemon";
import { settingsStore } from "@/components/settings/use-settings";

/** Reads an `infantry.notify` setting from this window's settings store. */
export const readNotifySetting: NotifySettingRead = (key) => settingsStore().getState().values[settingKey(NOTIFY_NODE, key)];

let ctx: AudioContext | null = null;

/** Plays built-in sound [name]; silent where there is no audio device. */
export function playTone(name: string): void {
  const notes = TONES[name];
  if (!notes) return;
  try {
    ctx ??= new AudioContext();
    const t0 = ctx.currentTime;
    for (const n of notes) {
      const osc = ctx.createOscillator();
      const gain = ctx.createGain();
      osc.type = "sine";
      osc.frequency.value = n.freq;
      gain.gain.setValueAtTime(0.0001, t0 + n.at);
      gain.gain.exponentialRampToValueAtTime(0.25, t0 + n.at + 0.01);
      gain.gain.exponentialRampToValueAtTime(0.0001, t0 + n.at + n.dur);
      osc.connect(gain).connect(ctx.destination);
      osc.start(t0 + n.at);
      osc.stop(t0 + n.at + n.dur + 0.02);
    }
  } catch {
    // no audio device: the toast is enough
  }
}

export function NoticeSoundHost() {
  useEffect(() => {
    settingsStore().getState().ensureLoaded();
    return daemonStore().getState().onFrame((env) => {
      const s = noticeSoundOf(env, readNotifySetting);
      if (s) playTone(s);
    });
  }, []);
  return null;
}
