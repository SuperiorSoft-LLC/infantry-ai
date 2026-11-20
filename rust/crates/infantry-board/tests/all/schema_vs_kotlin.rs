// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! The temp schema built from the .sq files equals a fresh database the Kotlin daemon created.
//! Runs only when INFANTRY_KOTLIN_DB names such a file (a temp state dir's infantry.db, never the owner's);
//! the file is read through a copy made with the sqlite backup API.
use std::collections::BTreeSet;

use infantry_board::schema::TempDb;
use rusqlite::{Connection, OpenFlags};

fn shape(c: &Connection) -> (i64, BTreeSet<String>) {
    let v: i64 = c.query_row("PRAGMA user_version", [], |r| r.get(0)).unwrap();
    let mut out = BTreeSet::new();
    let objects: Vec<(String, String)> = c
        .prepare("SELECT type, name FROM sqlite_master WHERE name NOT LIKE 'sqlite_%'")
        .unwrap()
        .query_map([], |r| Ok((r.get(0)?, r.get(1)?)))
        .unwrap()
        .map(Result::unwrap)
        .collect();
    for (ty, name) in objects {
        out.insert(format!("{ty} {name}"));
        if ty == "table" {
            let cols: Vec<String> = c
                .prepare(&format!("PRAGMA table_info('{name}')"))
                .unwrap()
                .query_map([], |r| {
                    Ok(format!(
                        "{}:{}:{}:{:?}:{}",
                        r.get::<_, String>(1)?,
                        r.get::<_, String>(2)?,
                        r.get::<_, i64>(3)?,
                        r.get::<_, Option<String>>(4)?,
                        r.get::<_, i64>(5)?
                    ))
                })
                .unwrap()
                .map(Result::unwrap)
                .collect();
            out.insert(format!("columns {name} {}", cols.join(",")));
        }
    }
    (v, out)
}

#[test]
fn temp_schema_matches_a_fresh_kotlin_db() {
    let Ok(path) = std::env::var("INFANTRY_KOTLIN_DB") else {
        eprintln!("INFANTRY_KOTLIN_DB unset: skipped");
        return;
    };
    let ours = TempDb::new("vs-kotlin");
    let copy = ours.dir.join("kotlin-copy.db");
    {
        let from = Connection::open_with_flags(&path, OpenFlags::SQLITE_OPEN_READ_ONLY).unwrap();
        let mut to = Connection::open(&copy).unwrap();
        rusqlite::backup::Backup::new(&from, &mut to).unwrap().run_to_completion(256, std::time::Duration::ZERO, None).unwrap();
    }
    let (kv, k) = shape(&Connection::open(&copy).unwrap());
    let (ov, o) = shape(&Connection::open(&ours.path).unwrap());
    assert_eq!(ov, kv, "user_version");
    let missing: Vec<_> = k.difference(&o).collect();
    let extra: Vec<_> = o.difference(&k).collect();
    assert!(missing.is_empty() && extra.is_empty(), "missing in ours: {missing:#?}\nextra in ours: {extra:#?}");
}
