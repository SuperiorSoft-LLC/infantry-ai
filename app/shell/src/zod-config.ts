// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// WEBUI-1: the desktop and Android windows run under a content security policy that allows no eval
// (src-tauri/tauri.conf.json). To pick its faster object parser, zod probes `new Function` when the first object schema
// is built; under that policy the probe is a CSP violation on every start. jitless skips the probe, and zod parses the
// same either way. main.tsx imports this module first, before any module that builds a schema.
import { config } from "zod";

config({ jitless: true });
