// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// RR-14: the stores that read ONE runner's machine (harness install status, providers, models, secrets), made once per runner name.
// `local` is the app's own: the secrets store is the shared one of Settings > Secrets. Any other runner reads through
// `runnerLink`, so every frame goes out as `runner_frame {runner, frame, data}` and the inner answer comes back (client runner-request.ts).
import {
  createHarnessInstallStore,
  createQueryStore,
  createSecretsStore,
  isLocalRunner,
  LOCAL_RUNNER,
  runnerLink,
  type HarnessInfo,
  type ModelGroup,
  type SecretsStore,
} from "@infantry/client";
import { daemonStore } from "@/daemon/daemon";
import { secretsStore } from "@/daemon/secrets-store";

export interface RunnerStores {
  runner: string;
  install: ReturnType<typeof createHarnessInstallStore>;
  providers: ReturnType<typeof createQueryStore<HarnessInfo[]>>;
  catalog: ReturnType<typeof createQueryStore<ModelGroup[]>>;
  secrets: SecretsStore;
}

const cache = new Map<string, RunnerStores>();

/** The stores of [runner] (`local` when left out). */
export function runnerStores(runner?: string | null): RunnerStores {
  const name = isLocalRunner(runner) ? LOCAL_RUNNER : (runner as string);
  const hit = cache.get(name);
  if (hit) return hit;
  const link = runnerLink(daemonStore().getState(), name);
  const made: RunnerStores = {
    runner: name,
    install: createHarnessInstallStore(link),
    // `refresh` makes the daemon rebuild its harness list now (it serves a cached one otherwise): sign-in and installs show at once.
    providers: createQueryStore<HarnessInfo[]>(link, {
      prefix: `setup-p-${name}`,
      request: "providers",
      body: () => JSON.stringify({ refresh: true }),
      answers: ["providers"],
      empty: [],
      parse: (raw) => (Array.isArray(raw) ? (raw as HarnessInfo[]) : null),
    }),
    catalog: createQueryStore<ModelGroup[]>(link, {
      prefix: `setup-m-${name}`,
      request: "models",
      answers: ["models"],
      empty: [],
      parse: (raw) => (Array.isArray(raw) ? (raw as ModelGroup[]) : null),
    }),
    secrets: name === LOCAL_RUNNER ? secretsStore() : createSecretsStore(link),
  };
  cache.set(name, made);
  return made;
}
