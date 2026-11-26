// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The tests import shell/src/host (AND-10), which reads import.meta.env the Vite way; the client package has no vite dependency.
interface ImportMetaEnv {
  readonly [key: string]: string | boolean | undefined;
}
interface ImportMeta {
  readonly env: ImportMetaEnv;
}
