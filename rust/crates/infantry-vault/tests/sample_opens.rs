// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! Manual check that a Kotlin-written keystore opens. Point `INFANTRY_VAULT_SAMPLE_DIR` at a TEMP COPY of a state dir
//! (keystore.json + keystore.key.dpapi / keystore.key) and run with `--ignored`. Prints counts only, never a name or value.
use std::sync::Arc;

use infantry_vault::limits::Limits;
use infantry_vault::Keystore;

#[test]
#[ignore]
fn kotlin_sample_opens() {
    let Ok(dir) = std::env::var("INFANTRY_VAULT_SAMPLE_DIR") else { return };
    let ks = Keystore::with(&dir, Arc::new(|_| None), infantry_vault::os_vault::detect(std::path::Path::new(&dir)), Limits::default());
    let protection = ks.protection().expect("the master key opens");
    let secrets = ks.snapshot().expect("snapshot");
    let fields: usize = secrets.iter().map(|s| s.fields.len()).sum();
    let stored = ks.names(None).unwrap().len() + ks.projects().iter().map(|p| ks.names(Some(p)).unwrap().len()).sum::<usize>();
    println!("protection={} stored={} opened={} fields={}", protection.wire(), stored, secrets.len(), fields);
    assert_eq!(stored, secrets.len(), "every stored secret opens");
}
