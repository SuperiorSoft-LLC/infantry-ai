// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The one secrets store of the app (the daemon vault's secret_state), shared by Settings > Secrets and the sign-in screens.
import { createSecretsStore, type SecretsStore } from "@infantry/client";
import { daemonStore } from "@/daemon/daemon";

let store: SecretsStore | null = null;
export function secretsStore(): SecretsStore {
  return (store ??= createSecretsStore(daemonStore().getState()));
}
